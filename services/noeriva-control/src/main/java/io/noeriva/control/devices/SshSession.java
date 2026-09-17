package io.noeriva.control.devices;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.password.*;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.compression.BuiltinCompressions;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.apache.sshd.core.CoreModuleProperties;
import static io.noeriva.control.devices.SshDriver.failure;

/** One authenticated interactive shell, pinned before password authentication, with no local SSH configuration. */
final class SshSession implements AutoCloseable {
    private final DeviceProtocol.Target target;
    private final long deadline;
    private final SshClient client=SshClient.setUpDefaultClient();
    private final AtomicBoolean closed=new AtomicBoolean(),keyRejected=new AtomicBoolean();
    private final Capture capture=new Capture();
    private volatile ClientSession session;
    private volatile ChannelShell channel;
    private String prompt;
    private int commands;
    SshSession(DeviceProtocol.Target target,long deadline){this.target=target;this.deadline=deadline;}
    void connect(DeviceProtocol.Secrets secrets)throws Exception {
        if(!DeviceAccessService.validSshPin(target.sshHostKeySha256()))throw failure("SSH_INVALID_HOST_KEY");
        if(target.username()==null||target.username().isBlank()||target.username().length()>120||target.username().chars().anyMatch(Character::isISOControl))throw failure("SSH_INVALID_SETTINGS");
        if(secrets==null||secrets.password()==null||secrets.password().isEmpty())throw failure("SSH_AUTHENTICATION_FAILED");
        InetAddress address;
        try{address=InetAddress.ofLiteral(target.address());}catch(IllegalArgumentException e){throw failure("SSH_INVALID_ADDRESS");}
        client.setHostConfigEntryResolver(HostConfigEntryResolver.EMPTY);
        client.setKeyIdentityProvider(KeyIdentityProvider.EMPTY_KEYS_PROVIDER);
        client.setPasswordIdentityProvider(PasswordIdentityProvider.EMPTY_PASSWORDS_PROVIDER);
        client.setUserAuthFactories(List.of(UserAuthPasswordFactory.INSTANCE));
        client.setUserInteraction(null);client.setAgentFactory(null);client.setClientProxyConnector(null);
        client.setCompressionFactories(List.of(BuiltinCompressions.none));
        Set<String> ciphers=Set.of("aes128-ctr","aes192-ctr","aes256-ctr","aes128-gcm@openssh.com","aes256-gcm@openssh.com","chacha20-poly1305@openssh.com");
        client.setCipherFactories(client.getCipherFactories().stream().filter(f->ciphers.contains(f.getName())).toList());
        client.setMacFactories(client.getMacFactories().stream().filter(f->f.getName().startsWith("hmac-sha2-")).toList());
        client.setSignatureFactories(client.getSignatureFactories().stream().filter(f->!f.getName().startsWith("ssh-rsa")&&!f.getName().startsWith("ssh-dss")).toList());
        client.setKeyExchangeFactories(client.getKeyExchangeFactories().stream().filter(f->!f.getName().contains("sha1")).toList());
        CoreModuleProperties.NIO_WORKERS.set(client,1);
        CoreModuleProperties.PASSWORD_PROMPTS.set(client,1);
        CoreModuleProperties.AUTH_TIMEOUT.set(client,Duration.ofMillis(timeout()));
        CoreModuleProperties.IDLE_TIMEOUT.set(client,Duration.ofSeconds(25));
        CoreModuleProperties.IO_CONNECT_TIMEOUT.set(client,Duration.ofMillis(timeout()));
        client.setServerKeyVerifier((s,remote,key)->{
            boolean valid=MessageDigest.isEqual(KeyUtils.getFingerPrint(BuiltinDigests.sha256,key).getBytes(StandardCharsets.US_ASCII),target.sshHostKeySha256().getBytes(StandardCharsets.US_ASCII));
            if(!valid)keyRejected.set(true);return valid;
        });
        try {
            check();client.start();
            session=client.connect(target.username(),new InetSocketAddress(address,target.port()),null,null).verify(timeout()).getSession();
            check();session.addPasswordIdentity(secrets.password());
            try{session.auth().verify(timeout());}catch(Exception e){throw failure(keyRejected.get()?"SSH_HOST_KEY_MISMATCH":"SSH_AUTHENTICATION_FAILED");}
            check();channel=session.createShellChannel();channel.setPtyType("dumb");channel.setPtyColumns(240);channel.setPtyLines(1000);
            channel.setOut(capture);channel.setErr(capture);channel.open().verify(timeout());
            awaitPrompt(true);
        }catch(Exception e){if(keyRejected.get())throw failure("SSH_HOST_KEY_MISMATCH");throw e;}
    }
    String command(String command)throws Exception{
        return command(command,0);
    }
    /** Running configuration can be larger than telemetry; retain the same session/output limits. */
    String readRunningConfiguration()throws Exception{
        return command("show running-config",30000);
    }
    private String command(String command,long budgetMillis)throws Exception{
        check();if(++commands>6)throw failure("SSH_COMMAND_LIMIT");
        if(!SshProfiles.allowedCommand(target.sshProfile(),command))throw failure("SSH_COMMAND_REJECTED");
        capture.reset();channel.getInvertedIn().write((command+"\n").getBytes(StandardCharsets.US_ASCII));channel.getInvertedIn().flush();
        return awaitPrompt(false,budgetMillis);
    }
    private String awaitPrompt(boolean initial)throws Exception{
        return awaitPrompt(initial,0);
    }
    private String awaitPrompt(boolean initial,long budgetMillis)throws Exception{
        long requestDeadline=Math.min(deadline,System.nanoTime()+Duration.ofMillis(budgetMillis>0?budgetMillis:timeout()).toNanos());int lastPager=-1,pages=0;
        Pattern expected=initial?Pattern.compile(target.sshProfile().equals("HUAWEI_IMANA")?"(?:^|\\n)(iMana:/->)\\s*$":"(?:^|\\n)([a-zA-Z0-9_.:/()\\-]{1,120}[#>])\\s*$"):
            Pattern.compile("(?:^|\\n)("+Pattern.quote(prompt)+")\\s*$");
        while(true){
            check();Capture.Snapshot snapshot=capture.snapshot();if(snapshot.overflow())throw failure("SSH_OUTPUT_LIMIT");
            String text=terminal(snapshot.bytes());var match=expected.matcher(text);
            if(match.find()){if(initial)prompt=match.group(1);return text.substring(0,match.start());}
            if(text.matches("(?s).*(?:--\\s*More\\s*--|<---\\s*More\\s*--->)\\s*$")&&snapshot.count()!=lastPager){
                if(++pages>64)throw failure("SSH_PAGINATION_LIMIT");lastPager=snapshot.count();
                channel.getInvertedIn().write(' ');channel.getInvertedIn().flush();
            }
            if(channel.isClosed()||session.isClosed())throw failure(capture.snapshot().overflow()?"SSH_OUTPUT_LIMIT":"SSH_CHANNEL_CLOSED");
            long remaining=(requestDeadline-System.nanoTime())/1_000_000;if(remaining<=0)throw failure("SSH_TIMEOUT");
            capture.await(Math.min(remaining,100));
        }
    }
    private int timeout(){check();return (int)Math.max(1,Math.min(Math.clamp(target.timeoutMillis(),250,10000),(deadline-System.nanoTime())/1_000_000));}
    private void check(){if(closed.get()||Thread.currentThread().isInterrupted())throw failure("SSH_CANCELLED");if(System.nanoTime()>=deadline)throw failure("SSH_TIMEOUT");}
    static String terminal(byte[] bytes){
        String input=new String(bytes,StandardCharsets.UTF_8).replaceAll("\\x1B\\[[0-?]*[ -/]*[@-~]","");
        var result=new StringBuilder(input.length());
        for(int i=0;i<input.length();i++){char c=input.charAt(i);if(c=='\b'){if(!result.isEmpty()&&result.charAt(result.length()-1)!='\n')result.deleteCharAt(result.length()-1);}else if(c=='\n'||c=='\t'||c>=32&&c!=127)result.append(c);}
        return result.toString();
    }
    @Override public void close(){if(closed.compareAndSet(false,true)){var ch=channel;if(ch!=null)ch.close(true);var s=session;if(s!=null)s.close(true);client.close(true);capture.signal();}}
    private static final class Capture extends OutputStream{
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();private boolean overflow;
        record Snapshot(byte[] bytes,int count,boolean overflow){}
        @Override public synchronized void write(int value)throws IOException{write(new byte[]{(byte)value},0,1);}
        @Override public synchronized void write(byte[] input,int offset,int length)throws IOException{
            if(overflow||bytes.size()+length>256*1024){overflow=true;notifyAll();throw new IOException("Bounded SSH output exceeded");}
            bytes.write(input,offset,length);notifyAll();
        }
        synchronized Snapshot snapshot(){return new Snapshot(bytes.toByteArray(),bytes.size(),overflow);}
        synchronized void reset(){bytes.reset();overflow=false;}
        synchronized void await(long millis)throws InterruptedException{wait(millis);}
        synchronized void signal(){notifyAll();}
    }
}

package io.noeriva.control.devices;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.server.*;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;

/** Pure synthetic peer. No OS shell, local user, persistent host key, or production credential. */
final class SshFixture implements AutoCloseable {
    final SshServer server=SshServer.setUpDefaultServer();
    final Map<String,String> replies=new ConcurrentHashMap<>();
    final List<String> commands=new CopyOnWriteArrayList<>();
    final AtomicInteger passwords=new AtomicInteger(),active=new AtomicInteger();
    final String pin;
    String prompt="iMana:/->";
    boolean hang;
    SshFixture() throws Exception {
        var keys=KeyPairGenerator.getInstance("RSA");keys.initialize(2048);var key=keys.generateKeyPair();
        pin=KeyUtils.getFingerPrint(key.getPublic());
        server.setHost("127.0.0.1");server.setPort(0);server.setKeyPairProvider(session->List.of(key));
        server.setPasswordAuthenticator((u,p,s)->{passwords.incrementAndGet();return u.equals("synthetic-user")&&p.equals("synthetic-password");});
        server.setShellFactory(channel->new Shell());
        replies.put("ipmcget -d version","IPMI Version: 2.0\r\nActive iMana Version: (U1029)7.35\r\n");
        replies.put("ipmcget -d fruinfo","Product Manufacturer : Huawei Technologies Co., Ltd.\r\nProduct Name : SYNTHETIC RH2288 V2\r\nProduct Serial Number : SYNTHETIC-SERIAL\r\n");
        replies.put("ipmcget -d health","System in health state.\r\n");
        replies.put("ipmcget -t sensor -d list","sensor name | value | unit | status | lnr | lc | lnc | unc | uc | unr | phys | nhys\r\nInlet Temp | 22.000 | degrees C | ok | na | na | na | na | 44 | 46 | 2 | 2\r\nFAN1 | 4224 | RPM | ok | na | na | na | na | na | na | 0 | 0\r\nPower1 | 20 | Watts | ok | na | na | na | na | na | na | 0 | 0\r\nCPU Temp | na | degrees C | na | na | na | na | na | na | na | 0 | 0\r\nPS Status | 0x0 | discrete | 0x8000 | na | na | na | na | na | na | na | na\r\n");
        server.start();
    }
    int port(){return server.getPort();}
    final class Shell implements Command {
        InputStream in;OutputStream out,err;ExitCallback exit;volatile Thread worker;
        @Override public void setInputStream(InputStream value){in=value;}
        @Override public void setOutputStream(OutputStream value){out=value;}
        @Override public void setErrorStream(OutputStream value){err=value;}
        @Override public void setExitCallback(ExitCallback value){exit=value;}
        void emit(String s)throws IOException{out.write(s.getBytes(StandardCharsets.UTF_8));out.flush();}
        @Override public void start(ChannelSession channel,Environment env){
            worker=Thread.ofVirtual().start(()->{
                active.incrementAndGet();
                try {
                    emit("SYNTHETIC fixture\r\n"+prompt);
                    var line=new StringBuilder();int b;
                    while((b=in.read())!=-1){
                        if(b=='\r')continue;
                        if(b!='\n'){line.append((char)b);continue;}
                        String cmd=line.toString();line.setLength(0);commands.add(cmd);
                        if(hang){Thread.sleep(30000);continue;}
                        emit(cmd+"\r\n");
                        String reply=replies.getOrDefault(cmd,"% Error: Invalid input at '^' marker.\r\n");
                        int more=reply.indexOf("[PAGE]");
                        if(more>=0){emit(reply.substring(0,more)+"--More--");in.read();emit("\b \b".repeat(8)+reply.substring(more+6));}
                        else emit(reply);
                        emit("\r\n"+prompt);
                    }
                }catch(Exception ignored){}finally{active.decrementAndGet();exit.onExit(0);}
            });
        }
        @Override public void destroy(ChannelSession channel){if(worker!=null)worker.interrupt();try{in.close();}catch(Exception ignored){}}
    }
    @Override public void close()throws Exception{server.stop(true);}
}

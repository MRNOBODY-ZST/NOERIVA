package io.noeriva.control.nat;

import java.net.DatagramSocket;
import java.time.Clock;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.Environment;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class NatRuntimeContextTest {
 @Test void productionKafkaListenerRegistersWithoutCircularBeanReferences(){
  try(var context=context(false,2055)){
   assertThatCode(context::refresh).doesNotThrowAnyException();
   assertThat(context.getBean(NatRuntime.class).isRunning()).isFalse();
   var registry=context.getBean(KafkaListenerEndpointRegistry.class);
   assertThat(registry.getListenerContainers()).hasSize(1);
   var container=registry.getListenerContainers().iterator().next();
   assertThat(container.getGroupId()).isEqualTo("noeriva-nat-sink-v1");
   assertThat(container.getContainerProperties().getTopics()).containsExactly("noeriva.nat.v1");
   assertThat(container.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.RECORD);
  }
 }
 @Test void enabledProductionReceiverStartsAndContextCloseReleasesPort()throws Exception{
  int port;try(var available=new DatagramSocket(0)){port=available.getLocalPort();}
  try(var context=context(true,port)){assertThatCode(context::refresh).doesNotThrowAnyException();assertThat(context.getBean(NatRuntime.class).isRunning()).isTrue();}
  try(var replacement=new DatagramSocket(port)){assertThat(replacement.isBound()).isTrue();}
 }
 private AnnotationConfigApplicationContext context(boolean enabled,int port){
  var c=new AnnotationConfigApplicationContext();c.getDefaultListableBeanFactory().setAllowCircularReferences(false);c.getEnvironment().setActiveProfiles("production");
  c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("synthetic-nat-runtime",Map.of("NOERIVA_NAT_RECEIVER_ENABLED",enabled,"NOERIVA_NAT_UDP_PORT",port)));
  c.register(NatRuntime.class,Dependencies.class);return c;
 }
 @Configuration(proxyBeanMethods=false) @EnableKafka
 static class Dependencies {
  @Bean JsonMapper json(){return new JsonMapper();}
  @Bean NatSourceStore sources(JsonMapper json){return new NatSourceStore(null,null,json,Clock.systemUTC());}
  @Bean NatHistory history(Environment env,JsonMapper json,NatSourceStore sources){return new NatHistory(WebClient.builder(),env,json,sources);}
  @Bean ConsumerFactory<String,String> consumers(){return new DefaultKafkaConsumerFactory<>(Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,"127.0.0.1:1"),new StringDeserializer(),new StringDeserializer());}
  @Bean ProducerFactory<String,String> producers(){return new DefaultKafkaProducerFactory<>(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,"127.0.0.1:1"),new StringSerializer(),new StringSerializer());}
  @Bean KafkaTemplate<String,String> kafka(ProducerFactory<String,String> producers){return new KafkaTemplate<>(producers);}
  // Run the real @EnableKafka bean/endpoint lifecycle without requiring a broker
  // for this wiring regression. The production factory itself is not replaced.
  @Bean static BeanPostProcessor disconnectedListener(){return new BeanPostProcessor(){@Override public Object postProcessAfterInitialization(Object bean,String name){if(bean instanceof ConcurrentKafkaListenerContainerFactory<?,?> factory)factory.setAutoStartup(false);return bean;}};}
 }
}

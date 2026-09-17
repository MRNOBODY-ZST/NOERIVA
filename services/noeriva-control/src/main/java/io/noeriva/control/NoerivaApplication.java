package io.noeriva.control;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages="io.noeriva")
@EnableScheduling
public class NoerivaApplication {
    public static void main(String[] args) { SpringApplication.run(NoerivaApplication.class, args); }
}

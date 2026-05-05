package com.roydon.dear;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DearAgentApplication implements CommandLineRunner {
    public static void main(String[] args) {
        SpringApplication.run(DearAgentApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        System.out.println("DearAgentApplication started...");
    }
}

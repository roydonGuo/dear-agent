package com.roydon.dear;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * DearAgentApplication
 *
 * @AUTHOR: roydon
 * @DATE: 2026/4/26
 **/
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

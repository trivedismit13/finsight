package com.finsight;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class FinSightApplication {

	public static void main(String[] args) {
		SpringApplication.run(FinSightApplication.class, args);
	}

}

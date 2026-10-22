package cn.voltmate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude=UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
public class VoltMateApplication {
    public static void main(String[] args) { SpringApplication.run(VoltMateApplication.class, args); }
}

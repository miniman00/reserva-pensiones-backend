package uy.pensiones;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PensionsApiApplication {
  public static void main(String[] args) {
    SpringApplication.run(PensionsApiApplication.class, args);
  }
}

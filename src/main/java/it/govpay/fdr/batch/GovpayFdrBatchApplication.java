package it.govpay.fdr.batch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Main application class for GovPay FDR Batch
 */
@SpringBootApplication
@EnableScheduling
public class GovpayFdrBatchApplication {

    public static void main(String[] args) {
        SpringApplication.run(GovpayFdrBatchApplication.class, args);
    }
}

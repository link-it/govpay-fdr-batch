package it.govpay.fdr.batch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Main application class for GovPay FDR Batch
 */
@SpringBootApplication(scanBasePackages = {"it.govpay.fdr.batch", "it.govpay.common.client"})
@EntityScan(basePackages = {"it.govpay.fdr.batch", "it.govpay.common.client", "it.govpay.common.entity"})
@EnableJpaRepositories(basePackages = {"it.govpay.fdr.batch"})
@EnableScheduling
public class GovpayFdrBatchApplication {

    public static void main(String[] args) {
        SpringApplication.run(GovpayFdrBatchApplication.class, args);
    }
}

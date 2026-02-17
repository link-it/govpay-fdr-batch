package it.govpay.fdr.batch.config;

import org.springframework.batch.core.Job;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import it.govpay.common.batch.runner.AbstractCronJobRunner;
import it.govpay.common.batch.runner.JobExecutionHelper;
import it.govpay.fdr.batch.Costanti;

/**
 * Runner per l'esecuzione da command line del job FDR Acquisition in modalità multi-nodo.
 * <p>
 * Attivo solo con profile "cron" (non "default").
 */
@Component
@Profile("cron")
public class CronJobRunner extends AbstractCronJobRunner {

    public CronJobRunner(
            JobExecutionHelper jobExecutionHelper,
            @Qualifier("fdrAcquisitionJob") Job fdrAcquisitionJob) {
        super(jobExecutionHelper, fdrAcquisitionJob, Costanti.FDR_ACQUISITION_JOB_NAME);
    }
}

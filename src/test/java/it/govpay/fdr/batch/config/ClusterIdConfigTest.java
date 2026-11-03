package it.govpay.fdr.batch.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.batch.test.MetaDataInstanceFactory;
import org.springframework.batch.test.StepScopeTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import it.govpay.common.batch.config.ClusterIdPredefinito;
import it.govpay.common.batch.metrics.BatchMetricsNodo;
import it.govpay.common.batch.runner.JobExecutionHelper;
import it.govpay.fdr.batch.GovpayFdrBatchApplication;
import it.govpay.fdr.batch.filesystem.FdrFileSystemReader;
import it.govpay.fdr.batch.gde.mapper.EventoFdrMapper;

/**
 * Verifica che il cluster-id del nodo sia lo stesso per il {@link JobExecutionHelper},
 * per gli eventi del GDE, per la presa in carico dei file ({@link FdrFileSystemReader})
 * e per il tag {@code nodo} delle metriche (govpay-common#35, #36).
 */
class ClusterIdConfigTest {

    /** Il reader e' {@code @StepScope}: il valore si legge dal bean reale dentro uno step. */
    static Object clusterIdDelReader(FdrFileSystemReader reader) throws Exception {
        return StepScopeTestUtils.doInStepScope(MetaDataInstanceFactory.createStepExecution(),
                () -> {
                    Object target = AopTestUtils.getUltimateTargetObject(reader);
                    return ReflectionTestUtils.getField(target, "clusterId");
                });
    }

    @Nested
    @SpringBootTest(classes = GovpayFdrBatchApplication.class)
    @ActiveProfiles("test")
    class SenzaProperty {

        @Autowired
        private JobExecutionHelper jobExecutionHelper;

        @Autowired
        private EventoFdrMapper eventoFdrMapper;

        @Autowired
        private FdrFileSystemReader fdrFileSystemReader;

        @Autowired
        private BatchMetricsNodo batchMetricsNodo;

        @Test
        void usaIlClusterIdPredefinitoDelNodo() throws Exception {
            String atteso = ClusterIdPredefinito.valore();

            assertThat(jobExecutionHelper.getClusterId()).isEqualTo(atteso);
            assertThat(ReflectionTestUtils.getField(eventoFdrMapper, "clusterId")).isEqualTo(atteso);
            assertThat(clusterIdDelReader(fdrFileSystemReader)).isEqualTo(atteso);
            assertThat(batchMetricsNodo.valore()).isEqualTo(atteso);
        }
    }

    @Nested
    @SpringBootTest(classes = GovpayFdrBatchApplication.class)
    @ActiveProfiles("test")
    @TestPropertySource(properties = "govpay.batch.cluster-id=nodo-1")
    class ConProperty {

        @Autowired
        private JobExecutionHelper jobExecutionHelper;

        @Autowired
        private EventoFdrMapper eventoFdrMapper;

        @Autowired
        private FdrFileSystemReader fdrFileSystemReader;

        @Autowired
        private BatchMetricsNodo batchMetricsNodo;

        @Test
        void usaIlClusterIdConfigurato() throws Exception {
            assertThat(jobExecutionHelper.getClusterId()).isEqualTo("nodo-1");
            assertThat(ReflectionTestUtils.getField(eventoFdrMapper, "clusterId")).isEqualTo("nodo-1");
            assertThat(clusterIdDelReader(fdrFileSystemReader)).isEqualTo("nodo-1");
            assertThat(batchMetricsNodo.valore()).isEqualTo("nodo-1");
            assertThat(batchMetricsNodo.fonte()).isEqualTo(BatchMetricsNodo.PROPERTY_CLUSTER_ID);
        }
    }
}

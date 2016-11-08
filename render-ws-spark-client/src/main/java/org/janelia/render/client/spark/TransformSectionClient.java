package org.janelia.render.client.spark;

import com.beust.jcommander.Parameter;
import mpicbg.trakem2.transform.AffineModel2D;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackStats;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.RenderDataClientParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.net.URISyntaxException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * Spark client for copying tiles from one stack to another.
 *
 * @author Eric Trautman
 */
public class TransformSectionClient implements Serializable {

    @SuppressWarnings("ALL")
    private static class Parameters extends RenderDataClientParameters {

        // NOTE: --baseDataUrl, --owner, and --project parameters defined in RenderDataClientParameters

        @Parameter(
                names = "--stack",
                description = "Name of source stack",
                required = true)
        private String stack;

        @Parameter(
                names = "--targetOwner",
                description = "Name of target stack owner (default is same as source stack owner)",
                required = false)
        private String targetOwner;

        @Parameter(
                names = "--targetProject",
                description = "Name of target stack project (default is same as source stack project)",
                required = false)
        private String targetProject;

        @Parameter(
                names = "--targetStack",
                description = "Name of target stack",
                required = false)
        private String targetStack;

        @Parameter(
                names = "--minZ",
                description = "Minimum Z value for sections to be copied",
                required = false)
        private Double minZ;

        @Parameter(
                names = "--maxZ",
                description = "Maximum Z value for sections to be copied",
                required = false)
        private Double maxZ;

        @Parameter(names = "--transformId", description = "Identifier for tranformation", required = true)
        private String transformId;

        @Parameter(names = "--transformClass", description = "Name of transformation implementation (java) class", required = true)
        private String transformClass;

        // TODO: figure out less hacky way to handle spaces in transform data string
        @Parameter(names = "--transformData", description = "Data with which transformation implementation should be initialized (expects values to be separated by ',' instead of ' ')", required = true)
        private String transformData;

        private boolean moveToOrigin = false;

        public String getTargetOwner() {
            if (targetOwner == null) {
                targetOwner = owner;
            }
            return targetOwner;
        }

        public String getTargetProject() {
            if (targetProject == null) {
                targetProject = project;
            }
            return targetProject;
        }

        public String getTargetStack() {
            if ((targetStack == null) || (targetStack.trim().length() == 0)) {
                targetStack = stack;
            }
            return targetStack;
        }

    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args, TransformSectionClient.class);

                LOG.info("runClient: entry, parameters={}", parameters);

                final TransformSectionClient client = new TransformSectionClient(parameters);
                client.run();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;

    public TransformSectionClient(final Parameters parameters) {
        this.parameters = parameters;
    }

    public void run()
            throws IOException, URISyntaxException {

        final SparkConf conf = new SparkConf().setAppName("TransformSectionClient");
        final JavaSparkContext sparkContext = new JavaSparkContext(conf);

        final String sparkAppId = sparkContext.getConf().getAppId();
        final String executorsJson = LogUtilities.getExecutorsApiJson(sparkAppId);

        LOG.info("run: appId is {}, executors data is {}", sparkAppId, executorsJson);


        final RenderDataClient sourceDataClient = new RenderDataClient(parameters.baseDataUrl,
                                                                       parameters.owner,
                                                                       parameters.project);

        final List<Double> zValues = sourceDataClient.getStackZValues(parameters.stack,
                                                                      parameters.minZ,
                                                                      parameters.maxZ);

        if (zValues.size() == 0) {
            throw new IllegalArgumentException("source stack does not contain any matching z values");
        }

        final RenderDataClient targetDataClient = new RenderDataClient(parameters.baseDataUrl,
                                                                       parameters.getTargetOwner(),
                                                                       parameters.getTargetProject());

        final StackMetaData targetStackMetaData = targetDataClient.getStackMetaData(parameters.targetStack);
        if (! targetStackMetaData.isLoading()) {
            throw new IllegalArgumentException("target stack must be in the loading state, meta data is " +
                                               targetStackMetaData);
        }

        final LeafTransformSpec stackTransform = new LeafTransformSpec(parameters.transformId,
                null,
                parameters.transformClass,
                parameters.transformData.replace(',', ' '));
//      make RDD
        final JavaRDD<Double> rddZValues = sparkContext.parallelize(zValues);

        final Function<Double, Integer> transformFunction = new Function<Double, Integer>() {

            final
            @Override
            public Integer call(final Double z)
                    throws Exception {

                LogUtilities.setupExecutorLog4j("z " + z);
                //get the source client
                final RenderDataClient sourceDataClient = new RenderDataClient(parameters.baseDataUrl,
                                                                               parameters.owner,
                                                                               parameters.project);
                //get the target client(which can be the same as the source)
                final RenderDataClient targetDataClient;
                if ((parameters.targetProject == null) ||
                        (parameters.getTargetProject().trim().length() == 0) ||
                        (parameters.getTargetProject().equals(parameters.project))) {
                    targetDataClient = sourceDataClient;
                } else {
                    targetDataClient = new RenderDataClient(parameters.baseDataUrl,
                            parameters.getTargetOwner(),
                            parameters.getTargetProject());
                }

                final ResolvedTileSpecCollection sourceCollection =
                        sourceDataClient.getResolvedTiles(parameters.stack, z);

                sourceCollection.addTransformSpecToCollection(stackTransform);
                sourceCollection.addReferenceTransformToAllTiles(stackTransform.getId(), false);

                //vs tile spec validation?
                sourceCollection.removeUnreferencedTransforms();

                targetDataClient.saveResolvedTiles(sourceCollection, parameters.targetStack, z);

                return sourceCollection.getTileCount();
            }
        };

        // assign a transformation to the RDD
        final JavaRDD<Integer> rddTileCounts = rddZValues.map(transformFunction);

        // use an action to get the results
        final List<Integer> tileCountList = rddTileCounts.collect();
        long total = 0;

        for (final Integer tileCount : tileCountList) {
            total += tileCount;
        }

        LOG.info("run: collected stats");
        LOG.info("run: saved {} tiles and transforms", total);

        sparkContext.stop();
    }

    private static final Logger LOG = LoggerFactory.getLogger(TransformSectionClient.class);
}

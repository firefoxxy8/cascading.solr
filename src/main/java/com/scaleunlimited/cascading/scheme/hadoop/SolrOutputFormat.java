package com.scaleunlimited.cascading.scheme.hadoop;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.UUID;

import org.apache.commons.io.FileUtils;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.RecordWriter;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.util.Progressable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cascading.flow.hadoop.util.HadoopUtil;
import cascading.tuple.Fields;
import cascading.tuple.Tuple;

import com.scaleunlimited.cascading.scheme.core.KeepAliveHook;
import com.scaleunlimited.cascading.scheme.core.SolrSchemeUtil;
import com.scaleunlimited.cascading.scheme.core.SolrWriter;

public class SolrOutputFormat extends FileOutputFormat<Tuple, Tuple> {
    private static final Logger LOGGER = LoggerFactory.getLogger(SolrOutputFormat.class);
    
    public static final String SOLR_CORE_PATH_KEY = "com.scaleunlimited.cascading.solr.corePath";
    public static final String SINK_FIELDS_KEY = "com.scaleunlimited.cascading.solr.sinkFields";
    public static final String MAX_SEGMENTS_KEY = "com.scaleunlimited.cascading.solr.maxSegments";
    public static final String DATA_DIR_PROPERTY_NAME_KEY = "com.scaleunlimited.cascading.solr.dataDirPropertyName";
    
    public static final int DEFAULT_MAX_SEGMENTS = 10;

    private static class SolrRecordWriter implements RecordWriter<Tuple, Tuple> {

        private Path _outputPath;
        private FileSystem _outputFS;
        
        private transient KeepAliveHook _keepAliveHook;
        private transient File _localIndexDir;
        private transient SolrWriter _solrWriter;
        
        public SolrRecordWriter(JobConf conf, String name, Progressable progress) throws IOException {
            
            // Copy Solr core directory from HDFS to temp local location.
            Path sourcePath = new Path(conf.get(SOLR_CORE_PATH_KEY));
            String coreName = sourcePath.getName();
            String tmpDir = System.getProperty("java.io.tmpdir");
            File localSolrCore = new File(tmpDir, "cascading.solr-" + UUID.randomUUID() + "/" + coreName);
            FileSystem sourceFS = sourcePath.getFileSystem(conf);
            sourceFS.copyToLocalFile(sourcePath, new Path(localSolrCore.getAbsolutePath()));
            
            // Figure out where ultimately the results need to wind up.
            _outputPath = new Path(FileOutputFormat.getTaskOutputPath(conf, name), "index");
            _outputFS = _outputPath.getFileSystem(conf);

            // Get the set of fields we're indexing.
            Fields sinkFields = HadoopUtil.deserializeBase64(conf.get(SINK_FIELDS_KEY), conf, Fields.class);
            
            int maxSegments = conf.getInt(MAX_SEGMENTS_KEY, DEFAULT_MAX_SEGMENTS);
            
            String dataDirPropertyName = conf.get(DATA_DIR_PROPERTY_NAME_KEY);
            
            // Set up local Solr home.
            File localSolrHome = SolrSchemeUtil.makeTempSolrHome(localSolrCore);

            // This is where data will wind up, inside of an index subdir.
            _localIndexDir = new File(localSolrHome, "data");

            _keepAliveHook = new HadoopKeepAliveHook(progress);
            
            _solrWriter = new SolrWriter(_keepAliveHook, sinkFields, dataDirPropertyName, _localIndexDir.getAbsolutePath(), localSolrCore, maxSegments) { };
        }
        
        @Override
        protected void finalize() throws Throwable {
            if (_solrWriter != null) {
                _solrWriter.cleanup();
                _solrWriter = null;
            }
            
            super.finalize();
        }
        
        @Override
        public void close(final Reporter reporter) throws IOException {
            _solrWriter.cleanup();
            
            // Finally we can copy the resulting index up to the target location in HDFS
            copyToHDFS();
        }

        private void copyToHDFS() throws IOException {
            File indexDir = new File(_localIndexDir, "index");
            
            // HACK!!! Hadoop has a bug where a .crc file locally with the matching name will
            // trigger an error, so we want to get rid of all such .crc files from inside of
            // the index dir.
            removeCrcFiles(indexDir);
            
            // Because we never write anything out, we need to tell Hadoop we're not hung.
            Thread reporterThread = startProgressThread();

            try {
                long indexSize = FileUtils.sizeOfDirectory(indexDir);
                LOGGER.info(String.format("Copying %d bytes of index from %s to %s", indexSize, _localIndexDir, _outputPath));
                _outputFS.copyFromLocalFile(true, new Path(indexDir.getAbsolutePath()), _outputPath);
            } finally {
                reporterThread.interrupt();
            }
        }
        
        private void removeCrcFiles(File dir) {
            File[] crcFiles = dir.listFiles(new FilenameFilter() {

                @Override
                public boolean accept(File dir, String name) {
                    return name.endsWith(".crc");
                }
            });
            
            for (File crcFile : crcFiles) {
                crcFile.delete();
            }
        }
        
        @Override
        public void write(Tuple key, Tuple value) throws IOException {
            _solrWriter.add(value);
        }
        
        /**
         * Fire off a thread that repeatedly calls Hadoop to tell it we're making progress.
         * @return
         */
        private Thread startProgressThread() {
            Thread result = new Thread() {
                @Override
                public void run() {
                    while (!isInterrupted()) {
                        _keepAliveHook.keepAlive();
                        
                        try {
                            sleep(10 * 1000);
                        } catch (InterruptedException e) {
                            interrupt();
                        }
                    }
                }
            };
            
            result.start();
            return result;
        }
        
    }
    
    @Override
    public void checkOutputSpecs(FileSystem ignored, JobConf job) throws IOException {
        // TODO anything to do here?
    }

    @Override
    public RecordWriter<Tuple, Tuple> getRecordWriter(FileSystem ignored, JobConf job, String name, Progressable progress) throws IOException {
        return new SolrRecordWriter(job, name, progress);
    }

}

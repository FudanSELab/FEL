

/*
 * Cloud9: A MapReduce Library for Hadoop
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You may
 * obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.yahoo.semsearch.fastlinking.io;

import edu.umd.cloud9.collection.wikipedia.WikipediaDocnoMapping;
import edu.umd.cloud9.collection.wikipedia.WikipediaPage;
import org.apache.commons.cli.*;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.SequenceFileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.log4j.Logger;

import java.io.IOException;

/**
 * Tool for repacking Wikipedia XML dumps into <code>SequenceFiles</code>.
 * 实际上就是读取维基百科的XML，然后转化为hadoop能够处理的方式,建立一个实体ID与文档的映射
 * <p>
 * hadoop \
 * jar target/FEL-0.1.0.jar \
 * com.yahoo.semsearch.fastlinking.io.RepackWikipedia \
 * -Dmapreduce.map.env="JAVA_HOME=/home/gs/java/jdk64/current" \
 * -Dmapreduce.reduce.env="JAVA_HOME=/home/gs/java/jdk64/current" \
 * -Dyarn.app.mapreduce.am.env="JAVA_HOME=/home/gs/java/jdk64/current" \
 * -Dmapred.job.map.memory.mb=6144 \
 * -Dmapreduce.map.memory.mb=6144 \
 * -Dmapred.child.java.opts="-Xmx2048m" \
 * -Dmapreduce.map.java.opts='-Xmx2g -XX:NewRatio=8 -XX:+UseSerialGC' \
 * -input wiki/${WIKI_MARKET}/${WIKI_DATE}/pages-articles.xml \
 * -mapping_file wiki/${WIKI_MARKET}/${WIKI_DATE}/docno.dat \
 * -output wiki/${WIKI_MARKET}/${WIKI_DATE}/pages-articles.block \
 * -wiki_language ${WIKI_MARKET} \
 * -compression_type block
 * </p>
 *
 * @author Jimmy Lin
 * @author Peter Exner
 */
public class RepackWikipedia extends Configured implements Tool {
    private static final Logger LOG = Logger.getLogger(RepackWikipedia.class);

    private static enum Records {
        TOTAL
    }

    ;

    private static class MyMapper extends Mapper<LongWritable, WikipediaPage, IntWritable, WikipediaPage> {
        private static final IntWritable docno = new IntWritable();
        private static final WikipediaDocnoMapping docnoMapping = new WikipediaDocnoMapping();

        @Override
        public void setup(Context context) {
            try {
                Path p = new Path(context.getConfiguration().get(DOCNO_MAPPING_FIELD));
                LOG.info("Loading docno mapping: " + p);

                FileSystem fs = FileSystem.get(context.getConfiguration());
                if (!fs.exists(p)) {
                    throw new RuntimeException(p + " does not exist!");
                }

                docnoMapping.loadMapping(p, fs);
            } catch (Exception e) {
                throw new RuntimeException("Error loading docno mapping data file!");
            }
        }

        @Override
        public void map(LongWritable key, WikipediaPage doc, Context context) throws IOException, InterruptedException {
            context.getCounter(Records.TOTAL).increment(1);
            String id = doc.getDocid();
            if (id != null) {
                // We're going to discard pages that aren't in the docno mapping.
                int n = docnoMapping.getDocno(id);
                if (n >= 0) {
                    docno.set(n);

                    context.write(docno, doc);
                }
            }
        }
    }

    private static final String DOCNO_MAPPING_FIELD = "DocnoMappingDataFile";

    private static final String INPUT_OPTION = "input";
    private static final String OUTPUT_OPTION = "output";
    private static final String MAPPING_FILE_OPTION = "mapping_file";
    private static final String COMPRESSION_TYPE_OPTION = "compression_type";
    private static final String LANGUAGE_OPTION = "wiki_language";

    @SuppressWarnings("static-access")
    @Override
    public int run(String[] args) throws Exception {
        Options options = new Options();
        options.addOption(OptionBuilder.withArgName("path").hasArg().withDescription("XML dump file").create(INPUT_OPTION));
        options.addOption(OptionBuilder.withArgName("path").hasArg().withDescription("output location").create(OUTPUT_OPTION));
        options.addOption(OptionBuilder.withArgName("path").hasArg().withDescription("mapping file").create(MAPPING_FILE_OPTION));
        options.addOption(OptionBuilder.withArgName("block|record|none").hasArg().withDescription("compression type").create(COMPRESSION_TYPE_OPTION));
        options.addOption(OptionBuilder.withArgName("en|sv|de").hasArg().withDescription("two-letter language code").create(LANGUAGE_OPTION));

        CommandLine cmdline;
        CommandLineParser parser = new GnuParser();
        try {
            cmdline = parser.parse(options, args);
        } catch (ParseException exp) {
            System.err.println("Error parsing command line: " + exp.getMessage());
            return -1;
        }

        if (!cmdline.hasOption(INPUT_OPTION) || !cmdline.hasOption(OUTPUT_OPTION) || !cmdline.hasOption(MAPPING_FILE_OPTION)
                || !cmdline.hasOption(COMPRESSION_TYPE_OPTION)) {
            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp(this.getClass().getName(), options);
            ToolRunner.printGenericCommandUsage(System.out);
            return -1;
        }

        String inputPath = cmdline.getOptionValue(INPUT_OPTION);
        String outputPath = cmdline.getOptionValue(OUTPUT_OPTION);
        String mappingFile = cmdline.getOptionValue(MAPPING_FILE_OPTION);
        String compressionType = cmdline.getOptionValue(COMPRESSION_TYPE_OPTION);

        if (!"block".equals(compressionType) && !"record".equals(compressionType) && !"none".equals(compressionType)) {
            System.err.println("Error: \"" + compressionType + "\" unknown compression type!");
            return -1;
        }

        String language = null;
        if (cmdline.hasOption(LANGUAGE_OPTION)) {
            language = cmdline.getOptionValue(LANGUAGE_OPTION);
            if (language.length() != 2) {
                System.err.println("Error: \"" + language + "\" unknown language!");
                return -1;
            }
        }

        // this is the default block size
        int blocksize = 1000000;

        Job job = Job.getInstance(getConf());
        job.setJarByClass(RepackWikipedia.class);
        job.setJobName(String.format("RepackWikipedia[%s: %s, %s: %s, %s: %s, %s: %s]", INPUT_OPTION, inputPath, OUTPUT_OPTION, outputPath,
                COMPRESSION_TYPE_OPTION, compressionType, LANGUAGE_OPTION, language));

        job.getConfiguration().set(DOCNO_MAPPING_FIELD, mappingFile);

        LOG.info("Tool name: " + this.getClass().getName());
        LOG.info(" - XML dump file: " + inputPath);
        LOG.info(" - output path: " + outputPath);
        LOG.info(" - docno mapping data file: " + mappingFile);
        LOG.info(" - compression type: " + compressionType);
        LOG.info(" - language: " + language);

        if ("block".equals(compressionType)) {
            LOG.info(" - block size: " + blocksize);
        }

        job.setNumReduceTasks(0);

        FileInputFormat.addInputPath(job, new Path(inputPath));
        FileOutputFormat.setOutputPath(job, new Path(outputPath));

        if ("none".equals(compressionType)) {
            FileOutputFormat.setCompressOutput(job, false);
        } else {
            FileOutputFormat.setCompressOutput(job, true);

            if ("record".equals(compressionType)) {
                SequenceFileOutputFormat.setOutputCompressionType(job, SequenceFile.CompressionType.RECORD);
            } else {
                SequenceFileOutputFormat.setOutputCompressionType(job, SequenceFile.CompressionType.BLOCK);
                job.getConfiguration().setInt("io.seqfile.compress.blocksize", blocksize);
            }
        }

        if (language != null) {
            job.getConfiguration().set("wiki.language", language);
        }

        job.setInputFormatClass(WikipediaPageInputFormat.class);
        job.setOutputFormatClass(SequenceFileOutputFormat.class);
        job.setOutputKeyClass(IntWritable.class);
        job.setOutputValueClass(WikipediaPageFactory.getWikipediaPageClass(language));
        //job.setOutputValueClass(EnglishWikipediaPage.class);

        job.setMapperClass(MyMapper.class);

        // Delete the output directory if it exists already.
        FileSystem.get(getConf()).delete(new Path(outputPath), true);

        return job.waitForCompletion(true) ? 0 : -1;

    }

    public RepackWikipedia() {
    }

    public static void main(String[] args) throws Exception {
        ToolRunner.run(new RepackWikipedia(), args);
    }
}

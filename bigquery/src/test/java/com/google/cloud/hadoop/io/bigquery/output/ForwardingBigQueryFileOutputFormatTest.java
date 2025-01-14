/*
 * Copyright 2017 Google LLC
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.cloud.hadoop.io.bigquery.output;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.cloud.hadoop.fs.gcs.InMemoryGoogleHadoopFileSystem;
import com.google.cloud.hadoop.io.bigquery.BigQueryFileFormat;
import com.google.cloud.hadoop.util.testing.CredentialConfigurationUtil;
import java.io.IOException;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.OutputCommitter;
import org.apache.hadoop.mapreduce.RecordWriter;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.TaskAttemptID;
import org.apache.hadoop.mapreduce.TaskID;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(JUnit4.class)
public class ForwardingBigQueryFileOutputFormatTest {

  /** Sample projectId for output. */
  private static final String TEST_PROJECT_ID = "domain:project";

  /** Sample datasetId for output. */
  private static final String TEST_DATASET_ID = "dataset";

  /** Sample tableId for output. */
  private static final String TEST_TABLE_ID = "table";

  /** Sample qualified tableId for output. */
  private static final String QUALIFIED_TEST_TABLE_ID =
      String.format("%s:%s.%s", TEST_PROJECT_ID, TEST_DATASET_ID, TEST_TABLE_ID);

  /** Sample output file format for the committer. */
  private static final BigQueryFileFormat TEST_FILE_FORMAT =
      BigQueryFileFormat.NEWLINE_DELIMITED_JSON;

  /** Sample output format class for the configuration. */
  @SuppressWarnings("rawtypes")
  private static final Class<? extends FileOutputFormat> TEST_OUTPUT_CLASS = TextOutputFormat.class;

  private static final String TEST_BUCKET_STRING = "gs://test_bucket";

  /** Sample raw output path for data. */
  private static final String TEST_OUTPUT_PATH_STRING = TEST_BUCKET_STRING + "/test_directory/";

  /** Sample output path for data. */
  private static final Path TEST_OUTPUT_PATH = new Path(TEST_OUTPUT_PATH_STRING);

  /** A sample task ID for the mock TaskAttemptContext. */
  private static final TaskAttemptID TEST_TASK_ATTEMPT_ID =
      new TaskAttemptID(new TaskID("sample_task", 100, false, 200), 1);

  /** GoogleHadoopFileSystem to use. */
  private InMemoryGoogleHadoopFileSystem ghfs;

  /** In memory file system for testing. */
  private Configuration conf;

  /** Sample Job context for testing. */
  private Job job;

  /** The output format being tested. */
  private ForwardingBigQueryFileOutputFormat<Text, Text> outputFormat;

  // Mocks.
  @Mock private TaskAttemptContext mockTaskAttemptContext;
  @Mock private FileOutputFormat<Text, Text> mockFileOutputFormat;
  @Mock private OutputCommitter mockOutputCommitter;
  @Mock private RecordWriter<Text, Text> mockRecordWriter;

  /** Sets up common objects for testing before each test. */
  @Before
  public void setUp() throws Exception {
    // Generate Mocks.
    MockitoAnnotations.initMocks(this);

    // Create the file system.
    ghfs = new InMemoryGoogleHadoopFileSystem();
    ghfs.mkdirs(new Path(TEST_BUCKET_STRING));

    // Create the configuration, but setup in the tests.
    job = Job.getInstance(InMemoryGoogleHadoopFileSystem.getSampleConfiguration());
    conf = job.getConfiguration();
    CredentialConfigurationUtil.addTestConfigurationSettings(conf);
    BigQueryOutputConfiguration.configureWithAutoSchema(
        conf,
        QUALIFIED_TEST_TABLE_ID,
        TEST_OUTPUT_PATH_STRING,
        TEST_FILE_FORMAT,
        TEST_OUTPUT_CLASS);

    // Configure mocks.
    when(mockTaskAttemptContext.getConfiguration()).thenReturn(conf);
    when(mockTaskAttemptContext.getTaskAttemptID()).thenReturn(TEST_TASK_ATTEMPT_ID);
    when(mockFileOutputFormat.getOutputCommitter(eq(mockTaskAttemptContext)))
        .thenReturn(mockOutputCommitter);
    when(mockFileOutputFormat.getRecordWriter(eq(mockTaskAttemptContext)))
        .thenReturn(mockRecordWriter);

    // Create and setup the output format.
    outputFormat = new ForwardingBigQueryFileOutputFormat<>();
    outputFormat.setDelegate(mockFileOutputFormat);
  }

  @After
  public void tearDown() throws IOException {
    verifyNoMoreInteractions(mockFileOutputFormat);
    verifyNoMoreInteractions(mockOutputCommitter);

    // File system changes leak between tests, always clean up.
    ghfs.delete(TEST_OUTPUT_PATH, true);
  }

  /** Test normal expected use of the function. */
  @Test
  public void testCheckOutputSpecs() throws IOException {
    outputFormat.checkOutputSpecs(mockTaskAttemptContext);

    verify(mockFileOutputFormat).checkOutputSpecs(eq(mockTaskAttemptContext));
  }

  /** Test an error is thrown when the output format's directory already exists. */
  @Test
  public void testCheckOutputSpecsAlreadyExists() throws IOException {
    // Setup configuration.
    ghfs.mkdirs(TEST_OUTPUT_PATH);

    IOException thrown =
        assertThrows(
            IOException.class, () -> outputFormat.checkOutputSpecs(mockTaskAttemptContext));
    assertThat(thrown)
        .hasMessageThat()
        .contains("The output path '" + TEST_OUTPUT_PATH + "' already exists.");
  }

  /** Test an error is throw when the user wants their output compressed. */
  @Test
  public void testCheckOutputSpecsCompressedOutput() throws IOException {
    // Setup configuration.
    FileOutputFormat.setCompressOutput(job, true);

    IOException thrown =
        assertThrows(
            IOException.class, () -> outputFormat.checkOutputSpecs(mockTaskAttemptContext));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Compression isn't supported for this OutputFormat.");
  }

  /** Test getOutputCommitter is calling the delegate and the mock OutputCommitter is returned. */
  @Test
  public void testGetOutputCommitter() throws IOException {
    OutputCommitter committer = outputFormat.getOutputCommitter(mockTaskAttemptContext);

    // Verify the delegate is being called and the mock OutputCommitter is returned.
    assertThat(committer).isEqualTo(mockOutputCommitter);
    verify(mockFileOutputFormat).getOutputCommitter(eq(mockTaskAttemptContext));
  }

  /** Test getRecordWriter is returning the mock RecordWriter. */
  @Test
  public void testGetRecordWriter() throws Exception {
    RecordWriter<Text, Text> recordWriter = outputFormat.getRecordWriter(mockTaskAttemptContext);

    // Verify the delegate is being called and the mock RecordWriter is returned.
    assertThat(recordWriter).isEqualTo(mockRecordWriter);
    verify(mockFileOutputFormat).getRecordWriter(eq(mockTaskAttemptContext));
  }

  /** Test createCommitter is calling the delegate and the mock OutputCommitter is returned. */
  @Test
  public void testCreateCommitter() throws IOException {
    OutputCommitter committer = outputFormat.createCommitter(mockTaskAttemptContext);

    // Verify the delegate is being called and the mock OutputCommitter is returned.
    assertThat(committer).isEqualTo(mockOutputCommitter);
    verify(mockFileOutputFormat).getOutputCommitter(eq(mockTaskAttemptContext));
  }

  /** Test getDelegate is returning the correct delegate. */
  @Test
  public void testGetDelegate() throws IOException {
    // Setup configuration.
    outputFormat.setDelegate(null);

    FileOutputFormat<Text, Text> delegate = outputFormat.getDelegate(conf);

    // Verify the delegate is the correct type.
    assertThat(delegate).isInstanceOf(TextOutputFormat.class);
  }
}

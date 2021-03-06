package com.harland.example.streaming;

import com.google.api.services.bigquery.model.TableRow;
import com.harland.example.common.bigquery.Schema;
import com.harland.example.common.model.TransferRecord;
import com.harland.example.common.options.AwsOptionsParser;
import com.harland.example.common.options.BigQueryImportOptions;
import com.harland.example.common.transform.ConvertToTransferRecordFn;
import com.harland.example.common.utils.JsonSchemaReader;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.Watch;
import org.joda.time.Duration;

import java.io.IOException;

public class StreamingFilePipeline {

  private static final String SCHEMA_FILE = "schema.json";

  public static void main(String... args) throws IOException {
    BigQueryImportOptions options =
        PipelineOptionsFactory.fromArgs(args).as(BigQueryImportOptions.class);

    // Configure AWS specific options
    AwsOptionsParser.formatOptions(options);

    runPipeline(options);
  }

  private static void runPipeline(BigQueryImportOptions options) throws IOException {
    Pipeline p = Pipeline.create(options);

    Schema schema = new Schema(JsonSchemaReader.readSchemaFile(SCHEMA_FILE));
    String bqColUser = schema.getColumnName(0);
    String bqColAmount = schema.getColumnName(1);

    p.apply(
            "ReadFromStorage",
            TextIO.read()
                .from(options.getBucketUrl() + "/*")
                .watchForNewFiles(Duration.ZERO, Watch.Growth.never()))

        // Convert each CSV row to a transfer record object
        .apply("ConvertToTransferRecord", ParDo.of(new ConvertToTransferRecordFn()))

        // Write the result to BigQuery.
        .apply(
            "WriteToBigQuery",
            BigQueryIO.<TransferRecord>write()
                .to(options.getBqTableName())
                .withSchema(schema.getTableSchema())
                .withFormatFunction(
                    (TransferRecord record) ->
                        new TableRow()
                            .set(bqColUser, record.getUser())
                            .set(bqColAmount, record.getAmount()))
                .withCreateDisposition(BigQueryIO.Write.CreateDisposition.CREATE_IF_NEEDED)
                .withWriteDisposition(BigQueryIO.Write.WriteDisposition.WRITE_APPEND));

    p.run();
  }
}

package net.bigtangle.server.performance;

public class DeltalakeTest {

	public static final String ENV_LOCAL = "local";
/* 
	SparkSession spark;
	// create tables

	private static SparkSession createSession() {
		SparkConf conf = new SparkConf()
		// .set("spark.local.dir", tmpDir) obsolete, should be set by YARN
		// .set("spark.sql.warehouse.dir", tmpDir) only needed for Spark SQL "CREATE
		// DATABASE"
		// .set("spark.streaming.stopGracefullyOnShutdown", "false")
		;

		conf.set("spark.master", "local[*]").set("spark.driver.bindAddress", "localhost")
				.set("spark.sql.shuffle.partitions", "3").set("spark.default.parallelism", "3")
				.set("spark.sql.extensions", "io.delta.sql.DeltaSparkSessionExtension")
				.set("spark.sql.catalog.spark_catalog", "org.apache.spark.sql.delta.catalog.DeltaCatalog");

		return SparkSession.builder().appName("test").config(conf).getOrCreate();
	}

	void cleanup() {

		spark.sql("DROP TABLE IF EXISTS student");
		spark.sql("DROP TABLE IF EXISTS student_source");
	}

	Dataset<Row> readByTableName(Integer startingVersion, String path) {
		return spark.read().format("delta")
				// .option("readChangeFeed", "true")
				// .option("startingVersion", startingVersion.toString())
				.load(path).orderBy("id");
	}

	@Test
	public void deltalake() throws IOException, InterruptedException {
		String path = "/data/testdelta";
		spark = createSession();

		try {
			// =============== Create table ===============
			spark.range(0, 10).selectExpr("CAST(id as INT) as id", "CAST(id as STRING) as name",
					"CAST(id % 4 + 18 as INT) as age").write().format("delta").mode("append").save(path); // v1

			System.out.println("(v1) Initial Table");

			DeltaTable table = DeltaTable.forPath(path);
			table.delete();
			table.toDF().show();

			spark.read().format("delta").load(path).orderBy("id").show();

			// table = DeltaTable.forPath(path);

			// =============== Perform UPDATE ===============

			System.out.println("(v2) Updated id -> id + 100");
			table.update(new HashMap<String, Column>() {
				{
					put("id", functions.expr("id + 100"));
				}
			}); // v2
			table.toDF().show();
			readByTableName(2, path).show();

			// =============== Perform DELETE ===============

			System.out.println("(v3) Deleted where id >= 107");
			table.delete(functions.expr("id >= 107")); // v3
			readByTableName(3, path).show();

			// =============== Perform partition DELETE ===============

			System.out.println("(v4) Deleted where age = 18");
			table.delete(functions.expr("age = 18")); // v4, partition delete
			readByTableName(4, path).show();

			// =============== Create source table for MERGE ===============

			Dataset<Row> deleted = spark.range(90, 103).selectExpr("CAST(id as INT) as id",
					"CAST(id as STRING) as name", "CAST(id % 4 + 18 as INT) as age");

			Dataset<Row> source = spark.range(90, 103).selectExpr("CAST(id as INT) as id", "CAST(id as STRING) as name",
					"CAST(id % 4 + 18 as INT) as age");
			// =============== Perform MERGE Delete===============
			table.as("target").merge(deleted.as("deleted"), "target.id = deleted.id").whenMatched().delete().execute(); // v5
			System.out.println("(v6) Merged with a deleted table");
			readByTableName(5, path).show();
			// =============== Perform MERGE upsert ===============

			table.as("target").merge(source.as("source"), "target.id = source.id").whenMatched()
					.update(new HashMap<String, Column>() {
						{
							put("id", functions.col("source.id"));
							put("age", functions.col("source.age"));
						}
					}).whenNotMatched().insertAll().execute(); // v5
			System.out.println("(v5) Merged with a source table");
			readByTableName(6, path).show();

		} finally {
			spark.stop();
		}
	}
	*/
}

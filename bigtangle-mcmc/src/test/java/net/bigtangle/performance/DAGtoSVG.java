package net.bigtangle.server.performance;

import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.UUID;

public class DAGtoSVG {

	public static void main(String[] args) throws IOException {
		// 1. Create your DAG using JGraphT
		DefaultDirectedGraph<String, DefaultEdge> dag = new DefaultDirectedGraph<>(DefaultEdge.class);

		dag.addVertex("A");
		dag.addVertex("B");
		dag.addVertex("C");
		dag.addVertex("D");

		dag.addEdge("A", "B");
		dag.addEdge("A", "C");
		dag.addEdge("B", "D");

		// 2. Convert DAG to DOT language format
		String dotString = toDot(dag);
		// 3. Generate the Graphviz .dot file
		File dotFile = createTempFile(".dot", dotString);

		// 4. Execute Graphviz `dot` to generate the SVG output
		File svgFile = createSVGFile(".svg", null);
		runDot(dotFile.getAbsolutePath(), svgFile.getAbsolutePath());

		// 5. Display or process the generated SVG
		System.out.println("SVG generated at " + svgFile.getAbsolutePath());

		// Displaying in the browser or a JavaFX WebView or etc is upto you
		// For now let's display the content as a test
		String content = Files.readString(Paths.get(svgFile.getAbsolutePath()));
		System.out.println(content);
	}

	// Convert JGraphT Graph to DOT format
	private static String toDot(DefaultDirectedGraph<String, DefaultEdge> dag) {
		StringBuilder sb = new StringBuilder();
		sb.append("digraph DAG {\n");
		for (String vertex : dag.vertexSet()) {
			sb.append("  \"").append(vertex).append("\";\n");
		}
		for (DefaultEdge edge : dag.edgeSet()) {
			String source = dag.getEdgeSource(edge);
			String target = dag.getEdgeTarget(edge);
			sb.append("  \"").append(source).append("\" -> \"").append(target).append("\";\n");
		}
		sb.append("}");
		return sb.toString();
	}

	private static void runDot(String dotFilePath, String svgFilePath) {
		try {
			// Construct the command
			String[] command = { "dot", "-Tsvg", dotFilePath, "-o", svgFilePath };
			ProcessBuilder pb = new ProcessBuilder(command);
			Process process = pb.start();

			// Read error stream to handle any errors during dot execution
			try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
				String line;
				while ((line = errorReader.readLine()) != null) {
					System.err.println(line);
				}
			}
			// Wait for the process to complete
			int exitCode = process.waitFor();
			if (exitCode != 0) {
				throw new RuntimeException("Graphviz 'dot' failed with exit code: " + exitCode);
			}
		} catch (IOException | InterruptedException e) {
			throw new RuntimeException("Error running Graphviz dot command", e);
		}
	}

	private static File createTempFile(String fileExtension, String fileContent) throws IOException {
		File tempFile = File.createTempFile("dag-", fileExtension);
		tempFile.deleteOnExit();
		if (fileContent != null) {
			try (BufferedWriter writer = new BufferedWriter(new FileWriter(tempFile))) {
				writer.write(fileContent);
			}
		}
		return tempFile;
	}
	private static File createSVGFile(String fileExtension, String fileContent) throws IOException {
		File tempFile = new File("logs", "Dag" + fileExtension);
		//tempFile.deleteOnExit();
		if (fileContent != null) {
			try (BufferedWriter writer = new BufferedWriter(new FileWriter(tempFile))) {
				writer.write(fileContent);
			}
		}
		return tempFile;
	}
}
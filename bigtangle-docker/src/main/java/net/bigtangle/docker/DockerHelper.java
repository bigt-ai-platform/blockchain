package net.bigtangle.docker;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class DockerHelper {

    private final Log LOG = LogFactory.getLog(getClass().getName());

    public   List<ResultExecution> shellExecute(List<String> commands) throws IOException, InterruptedException    {
        List<ResultExecution> re = new ArrayList<ResultExecution>();
        for (String command : commands) {
            re.add(shellExecute(command ));
        }
        return re;
    }

    public   ResultExecution shellExecute(String command ) throws IOException, InterruptedException   {
        ResultExecution aResultExcecution = new ResultExecution();
        aResultExcecution.setCommandtext(command);
        aResultExcecution.setResult(shellExecuteLocal(command));

        aResultExcecution.checkError();
        return aResultExcecution;
    }

    public   ResultExecution shellExecute(ShellExecute aShellExecute ) throws Exception  {
        ResultExecution aResultExcecution = new ResultExecution();
        aResultExcecution.setCommandtext(aShellExecute.getCmd());
        aResultExcecution.setResult(shellExecuteLocal(aShellExecute));
        // LOG.debug(aResultExcecution.toString());
        aResultExcecution.checkError();
        return aResultExcecution;

    }

    public   String shellExecuteLocal(List<String> remoteCommandList) throws IOException, InterruptedException   {
        StringWriter re = new StringWriter();
        for (String s : remoteCommandList) {
            re.append(shellExecuteLocal(s));
        }
        return re.toString();
    }

 
    
    public   String shellExecuteLocal(String remoteCommand) throws IOException, InterruptedException   {

        LOG.debug(remoteCommand);
        StringBuffer output = new StringBuffer();
        Process p;

        p = Runtime.getRuntime().exec(remoteCommand);
        p.waitFor();
        String line ;
        if (p.exitValue() == 0) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));

            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            return output.toString();
        } else {
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(p.getErrorStream()));
            while ((line = errorReader.readLine()) != null) {
                output.append(line).append("\n");
            }
            errorReader.close();
            throw new RuntimeException(output.toString());
        }
    }

    public String shellExecuteLocal(ShellExecute aShellExecute) throws IOException, InterruptedException  {
        // LOG.debug(" create file at filelocation"
        // + aShellExecute.getFilelocation());

        // write it to filelocation
        FileOutputStream out = new FileOutputStream(new File(aShellExecute.getFilelocation()));
        try {
            out.write(aShellExecute.getFile());
        } finally {
            out.close();
        }
        // LOG.debug(" Finish create file at filelocation");

        return shellExecuteLocal(aShellExecute.getCmd());
    }

   

}

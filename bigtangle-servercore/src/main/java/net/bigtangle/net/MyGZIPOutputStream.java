package net.bigtangle.net;

import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.GZIPOutputStream;

public class MyGZIPOutputStream  extends GZIPOutputStream {

    public MyGZIPOutputStream(OutputStream out) throws IOException {
        super(out);
    }

    public long getBytesRead() {
        return def.getBytesRead();
    }

    public long getBytesWritten() {
        return def.getBytesWritten();
    }

    public void setLevel(int level) {
        def.setLevel(level);
    }
  }

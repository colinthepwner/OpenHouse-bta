package openhouse.mod.voice.mp3;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.swing.text.html.HTMLDocument;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class Sound extends FilterInputStream {
  private Decoder.SoundData soundData;
  private int index;
  private AudioFormat audioFormat;

  public Sound(InputStream in) throws IOException {
    super(Objects.requireNonNull(in, "The specified InputStream must be non-null!"));
    soundData = Decoder.init(in);
    if(soundData == null) {
      throw new IOException("No MPEG data in the specified input stream!");
    }
  }

  @Override
  public int read() throws IOException {
    if(index == -1)
      return -1;
    if(index == soundData.samplesBuffer.length) {
      if(!Decoder.decodeFrame(soundData)) {
        index = -1;
        soundData.samplesBuffer = null;
        return -1;
      }
      index = 1;
      return soundData.samplesBuffer[0] & 0xFF;
    }
    return soundData.samplesBuffer[index++] & 0xFF;
  }

  @Override
  public int read(byte[] b) throws IOException {
    return read(b, 0, b.length);
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    if (b == null) {
      throw new NullPointerException();
    } else if (off < 0 || len < 0 || len > b.length - off) {
      throw new IndexOutOfBoundsException();
    } else if (len == 0) {
      return 0;
    }
    if(index == -1)
      return -1;
    int len_ = len;
    while(len > 0) {
      if(index == soundData.samplesBuffer.length) {
        if(!Decoder.decodeFrame(soundData)) {
          index = -1;
          soundData.samplesBuffer = null;
          return len_ == len ? -1 : len_ - len;
        }
        index = 0;
      }
      int remaining = soundData.samplesBuffer.length - index;
      if(remaining > 0) {
        if(remaining >= len) {
          System.arraycopy(soundData.samplesBuffer, index, b, off, len);
          index += len;
          return len_;
        }
        System.arraycopy(soundData.samplesBuffer, index, b, off, remaining);
        off += remaining;
        len -= remaining;
        index = soundData.samplesBuffer.length;
      }
    }
    throw new IllegalStateException("Shouldn't happen (internal error)");
  }

  @Override
  public long skip(long n) throws IOException {

    return super.skip(n);
  }

  @Override
  public int available() throws IOException {
    if(soundData.samplesBuffer == null)
      return 0;
    return soundData.samplesBuffer.length - index;
  }

  @Override
  public void close() throws IOException {
    if(in != null) {
      in.close();
      in = null;
      soundData.samplesBuffer = null;
    }
    index = -1;
  }

  @Override
  public synchronized void mark(int readlimit) {
    super.mark(readlimit);
  }

  @Override
  public synchronized void reset() throws IOException {
    throw new IOException("mark/reset not supported");
  }

  @Override
  public boolean markSupported() {
    return false;
  }

  public int decodeFullyInto(OutputStream os) throws IOException {
    Objects.requireNonNull(os);
    if(index == -1)
      return 0;
    int remaining = soundData.samplesBuffer.length - index;
    if(remaining > 0) {
      os.write(soundData.samplesBuffer, index, remaining);
    }
    int read = remaining;
    while(!Decoder.decodeFrame(soundData)) {
      os.write(soundData.samplesBuffer);
      read += soundData.samplesBuffer.length;
    }
    soundData.samplesBuffer = null;
    index = -1;
    return read;
  }

  public int getSamplingFrequency() {
    return soundData.frequency;
  }

  public boolean isStereo() {
    return soundData.stereo == 1;
  }

  public AudioFormat getAudioFormat() {
    if (audioFormat == null) {
      audioFormat = new AudioFormat(getSamplingFrequency(), 16, isStereo() ? 2 : 1, true, false);
    }
    return audioFormat;
  }

}

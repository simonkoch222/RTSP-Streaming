import static java.util.logging.Level.WARNING;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

/**
 * Klasse zum Einlesen eines MJPEG-Videos.
 *
 * @author Elisa Zschorlich (s70342)
 */
public class VideoReader {

  private FileInputStream fileInputStream;
  private boolean isClosed = true;

  int bufferSize;
  byte[] buffer;
  int bufferOffset;

  /**
   * Initialisiert den Video-Reader inkl. File Input Stream.
   *
   * @param videoFilePath Pfad für das Video
   * @throws FileNotFoundException falls das Video nicht gefunden werden kann
   */
  public VideoReader(final String videoFilePath) throws FileNotFoundException {
    // Öffnet den Input-Stream
    this.fileInputStream =
        new FileInputStream(videoFilePath) {

          /** Schließe den Input-Stream und setze das entsprechende Flag. */
          @Override
          public void close() throws IOException {
            isClosed = true;
            super.close();
          }

          /** Prüft vor dem Lesen eines weiteren Bytes, ob der Input-Stream noch geöffnet ist. */
          @Override
          public int read(byte[] b, int off, int len) throws IOException {
            return isClosed ? -1 : super.read(b, off, len);
          }
        };
    this.isClosed = false;

    this.bufferSize = 65536;
    this.buffer = new byte[this.bufferSize];
    this.bufferOffset = 0;
  }

  /** Schließt den Input-Stream, wenn dieser initialisiert und nicht geschlossen ist. */
  public void close() {
    if (fileInputStream != null && !isClosed) {
      try {
        fileInputStream.close();
      } catch (IOException e) {
        Logger.getGlobal()
            .log(WARNING, "FileInputStream des VideoReader konnte nicht geschlossen werden.");
      }
    }
  }

  /**
   * Liest das nächste JPEG-Bild aus der MJPEG-Videodatei ein.
   *
   * @return das eingelesene JPEG-Bild als Byte Array, falls dieses vollständig eingelesen werden
   *     konnte, sonst NULL.
   * @throws IOException IOException
   */
  public byte[] readNextImage() throws IOException {
    byte[] image = new byte[0];
    boolean jpegFound = false;

    // Prüft das der Input-Stream initialisiert und nicht geschlossen ist.
    if (fileInputStream != null && !isClosed) {
      // Zähler für enthaltene SOI-Marker, für die noch kein EOI gelesen wurde.
      int soiCount = 0;

      // Lese solange Bytes, bis Bild gefunden oder Dateiende
      while (!jpegFound) {
        int soiPos = -1;
        int eoiPos = -1;
        int nbytes = fileInputStream.read(buffer, bufferOffset, buffer.length - bufferOffset);
        if (nbytes == -1) {
          break;
        }
        bufferOffset += nbytes;

        // Durchsuche gelesene Daten
        for (int i = 0; i < bufferOffset-1; i++) {
          if (buffer[i] == JpegFrame.MARKER_TAG_START) {
            if (buffer[i+1] == JpegFrame.SOI_MARKER[1]) {
              if (soiCount == 0) {
                soiPos = i;
              }
              soiCount++;
            } else if (buffer[i+1] == JpegFrame.EOI_MARKER[1]) {
              soiCount--;
              if (soiCount == 0) {
                eoiPos = i+2; // EOI Marker mit kopieren
                jpegFound = true;
                break;
              }
            }
            // else: Andere Marker oder Daten
          }
        }

        int copyStart = 0;
        int copyEnd = bufferOffset;

        if (soiPos != -1) {
          copyStart = soiPos;
        }
        if (eoiPos != -1) {
          copyEnd = eoiPos;
        }
        if (soiCount == 0 && soiPos == -1 && eoiPos == -1) {
          copyStart = -1;
          copyEnd = -1;
        }

        if (copyStart != -1 && copyEnd != -1) {
          int copyLength = copyEnd - copyStart;
          byte[] tmpImage = new byte[image.length + (copyLength)];
          System.arraycopy(image, 0, tmpImage, 0, image.length);
          System.arraycopy(buffer, copyStart, tmpImage, image.length, copyLength);
          image = tmpImage;

          buffer = Arrays.copyOfRange(buffer, copyEnd, copyEnd + bufferSize);
          bufferOffset = bufferSize - copyEnd;
        } else {
          bufferOffset = 0;
        }
      }

      // Input-Stream wurde geschlossen oder es konnten keine Bytes gelesen werden.
      if (this.isClosed || !jpegFound) {
        return null;
      }

    }
    if (image.length == 0) {
      return null;
    } else {
      return image;
    }
  }
}

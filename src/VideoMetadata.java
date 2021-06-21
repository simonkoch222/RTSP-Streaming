/** Structure holding metadata of video files
 *
 * @author Emanuel Günther (s76954)
 */
public class VideoMetadata {
    private int framerate;
    private double duration; // in seconds

    public VideoMetadata(int framerate, double duration) {
        this.framerate = framerate;
        this.duration = duration;
    }

    public VideoMetadata(int framerate) {
        this(framerate, 0.0);
    }

    public int getFramerate() {
        return this.framerate;
    }

    public double getDuration() {
        return this.duration;
    }
}


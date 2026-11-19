import android.content.ContextWrapper;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import java.io.IOException;

public class MediaExtractorContractTest {
    public static void main(String[] args) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        try { ex.setDataSource((String) null); throw new AssertionError("null path"); } catch (IllegalArgumentException expected) {}
        try { ex.setDataSource(""); throw new AssertionError("empty path"); } catch (IOException expected) {}
        try { ex.setDataSource(new ContextWrapper(null), (Uri) null, null); throw new AssertionError("null uri"); } catch (IllegalArgumentException expected) {}
        try { ex.setDataSource(new ContextWrapper(null), Uri.parse("https://example.invalid/v.mp4"), null); throw new AssertionError("https uri"); } catch (IOException expected) {}
        try { ex.setDataSource(new ContextWrapper(null), Uri.parse("ftp://example.invalid/a.mp3"), null); throw new AssertionError("ftp uri"); } catch (IOException expected) {}
        try { ex.setDataSource(new ContextWrapper(null), Uri.parse("rtsp://example.invalid/live"), null); throw new AssertionError("rtsp uri"); } catch (IOException expected) {}
        try { ex.getTrackFormat(0); throw new AssertionError("getTrackFormat(0) uninitialized"); } catch (IllegalStateException expected) {}
        try { ex.getTrackFormat(-1); throw new AssertionError("getTrackFormat(-1) uninitialized"); } catch (IllegalStateException expected) {}
        try { ex.selectTrack(0); throw new AssertionError("selectTrack(0) empty"); } catch (IllegalArgumentException expected) {}
        try { ex.selectTrack(-1); throw new AssertionError("selectTrack(-1) empty"); } catch (IllegalArgumentException expected) {}
        try { ex.unselectTrack(0); throw new AssertionError("unselectTrack(0) empty"); } catch (IllegalArgumentException expected) {}
        try { ex.unselectTrack(-1); throw new AssertionError("unselectTrack(-1) empty"); } catch (IllegalArgumentException expected) {}
        ex.release();
        System.out.println("PASS: MediaExtractorContractTest");
    }
}

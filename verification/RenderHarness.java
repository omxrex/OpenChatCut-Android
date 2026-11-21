import com.openchatcut.android.core.EditResult;
import com.openchatcut.android.core.EditorEngine;
import com.openchatcut.android.model.CaptionsData;
import com.openchatcut.android.model.MediaAsset;
import com.openchatcut.android.model.MediaKind;
import com.openchatcut.android.model.ProjectDoc;
import com.openchatcut.android.model.TimelineState;
import com.openchatcut.android.media.RenderResult;
import com.openchatcut.android.media.TimelineRenderer;

import kotlin.Unit;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Runs the app's REAL compiled Kotlin classes under ART to verify timeline edits
 * and FFmpegKit export on an aarch64 Android device. No mocks are involved.
 *
 * Build and run instructions: see verification/README.md.
 */
public class RenderHarness {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("usage: RenderHarness <input-video> <output-video>");
        }
        String video = args[0];
        String output = args[1];

        MediaAsset asset = new MediaAsset(
                "asset-test-1", "test-clip.mp4", video, MediaKind.VIDEO,
                12000L, 360, 720, 480, 30.0, 4606704L, "test-clip.mp4", "", true);
        List<MediaAsset> assets = new ArrayList<>();
        assets.add(asset);
        TimelineState timeline = new TimelineState(30, 1920, 1080,
                Collections.emptyList(), Collections.emptyList(),
                new CaptionsData(false, "bold-outline", Collections.emptyList()), null);
        ProjectDoc project = new ProjectDoc(7, assets, timeline, "Harness Project");

        EditorEngine engine = new EditorEngine(project);
        EditResult add = engine.addToTimeline(asset.getId(), null);
        System.out.println("ADD ok=" + add.getOk() + " msg=" + add.getMessage());

        String itemId = engine.getProject().getTimeline().getItems().get(0).getId();
        EditResult split = engine.split(itemId, 180);
        System.out.println("SPLIT ok=" + split.getOk() + " msg=" + split.getMessage());
        System.out.println("ITEMS after edits=" + engine.getProject().getTimeline().getItems().size());

        long t0 = System.currentTimeMillis();
        RenderResult result = TimelineRenderer.INSTANCE.render(engine.getProject(), output,
                (pct, msg) -> {
                    System.out.println("PROGRESS " + pct + "% " + msg);
                    return Unit.INSTANCE;
                });
        System.out.println("RENDER ok=" + result.getOk() + " msg=" + result.getMessage());
        System.out.println("RENDER path=" + result.getOutputPath()
                + " bytes=" + result.getSizeBytes() + " durationMs=" + result.getDurationMs()
                + " elapsedMs=" + (System.currentTimeMillis() - t0));
        System.out.println("FILE_EXISTS=" + new File(output).exists());
    }
}

import com.openchatcut.android.ai.AgentTools;
import com.openchatcut.android.ai.ToolOutcome;
import com.openchatcut.android.core.EditorEngine;
import com.openchatcut.android.model.CaptionsData;
import com.openchatcut.android.model.MediaAsset;
import com.openchatcut.android.model.MediaKind;
import com.openchatcut.android.model.ProjectDoc;
import com.openchatcut.android.model.TimelineState;
import com.openchatcut.android.model.TimelineItem;

import android.content.ContextWrapper;

import kotlin.Unit;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Executes the app's REAL agent tools against the REAL editor engine under ART.
 * This verifies that tool instructions mutate the timeline; it does not involve
 * a fake model response. The AI transport itself requires a live Ollama daemon.
 */
public class ToolHarness {
    private static void log(String line) {
        System.out.println(line);
        System.out.flush();
    }

    private static void run(AgentTools tools, String name, String json) throws Exception {
        ToolOutcome outcome = tools.execute(name, new JSONObject(json));
        log("TOOL " + name + " ok=" + outcome.getOk() + " msg=" + outcome.getMessage());
    }

    public static void main(String[] args) throws Exception {
        log("START");
        String video = args[0];
        MediaAsset asset = new MediaAsset(
                "asset-tool-1", "test-clip.mp4", video, MediaKind.VIDEO,
                12000L, 360, 720, 480, 30.0, 4606704L, "test-clip.mp4", "", true);
        log("ASSET_CREATED");
        List<MediaAsset> assets = new ArrayList<>();
        assets.add(asset);
        TimelineState timeline = new TimelineState(30, 1920, 1080,
                Collections.emptyList(), Collections.emptyList(),
                new CaptionsData(false, "bold-outline", Collections.emptyList()), null);
        log("PROJECT_READY");
        EditorEngine engine = new EditorEngine(new ProjectDoc(7, assets, timeline, "Tool Harness"));
        log("ENGINE_READY");
        engine.addToTimeline(asset.getId(), null);
        log("ADDED");

        String firstId = engine.getProject().getTimeline().getItems().get(0).getId();
        log("FIRST_ID=" + firstId);
        AgentTools tools = new AgentTools(new ContextWrapper(null), engine,
                s -> { log("STATUS " + s); return Unit.INSTANCE; });
        log("TOOLS_READY");

        log("CATALOG_SIZE=" + tools.catalog().size());
        run(tools, "read_timeline", "{}");
        run(tools, "split_item", "{\"itemId\":\"" + firstId + "\",\"atFrame\":180}");
        log("ITEMS_AFTER_SPLIT=" + engine.getProject().getTimeline().getItems().size());

        List<String> ids = new ArrayList<>();
        for (TimelineItem item : engine.getProject().getTimeline().getItems()) {
            ids.add(item.getId());
        }
        run(tools, "add_transition", "{\"outgoingItemId\":\"" + ids.get(0)
                + "\",\"incomingItemId\":\"" + ids.get(1)
                + "\",\"type\":\"CROSS_DISSOLVE\",\"durationFrames\":15}");
        log("TRANSITIONS=" + engine.getProject().getTimeline().getTransitions().size());

        run(tools, "set_aspect_ratio", "{\"ratio\":\"9:16\"}");
        log("CANVAS=" + engine.getProject().getTimeline().getWidth()
                + "x" + engine.getProject().getTimeline().getHeight());

        run(tools, "undo_last_change", "{}");
        log("CANVAS_AFTER_UNDO=" + engine.getProject().getTimeline().getWidth()
                + "x" + engine.getProject().getTimeline().getHeight());
        run(tools, "redo_last_change", "{}");
        log("CANVAS_AFTER_REDO=" + engine.getProject().getTimeline().getWidth()
                + "x" + engine.getProject().getTimeline().getHeight());
        System.exit(0);
    }
}

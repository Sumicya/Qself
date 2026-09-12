/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.ui;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import io.github.qauxv.dsl.cell.TitleValueCell;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import kotlin.Unit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, manifest = Config.NONE, qualifiers = "w412dp-h915dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SettingsVisualTest {
    private Context context(boolean dark, float fontScale, int width, boolean rtl) {
        Configuration config = new Configuration(RuntimeEnvironment.getApplication().getResources().getConfiguration());
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) |
            (dark ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        config.fontScale = fontScale;
        config.screenWidthDp = width;
        if (rtl) config.setLayoutDirection(new java.util.Locale("ar"));
        ContextThemeWrapper context = new ContextThemeWrapper(
            RuntimeEnvironment.getApplication().createConfigurationContext(config),
            androidx.appcompat.R.style.Theme_AppCompat_DayNight);
        return context;
    }

    private SettingsHomeView home(Context context, boolean recording, int mode, List<String> clicks) {
        SettingsHomeView view = new SettingsHomeView(context);
        view.setBackground(SettingsVisuals.backdrop(SettingsVisuals.palette(context, mode)));
        view.bind(new SettingsHomeView.State("QQ 9.2.10", recording, false), mode, id -> { clicks.add(id); return Unit.INSTANCE; });
        return view;
    }

    private void layout(View view, int width) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        view.layout(0, 0, width, view.getMeasuredHeight());
    }

    private void verifyTextBounds(View view) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            if (text.getVisibility() == View.VISIBLE && text.length() != 0) {
                assertNotNull(text.getText().toString(), text.getLayout());
                assertTrue("clipped text: " + text.getText(),
                    text.getLayout().getHeight() <= text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom());
                assertTrue("zero text width: " + text.getText(), text.getWidth() > 0);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) verifyTextBounds(group.getChildAt(i));
        }
    }

    private void render(String name, View view) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight() + 28, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        view.draw(canvas);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.rgb(238, 238, 238));
        canvas.drawRect(0, view.getHeight(), view.getWidth(), bitmap.getHeight(), paint);
        paint.setColor(Color.DKGRAY);
        paint.setTextSize(9);
        canvas.drawText("ROBOLECTRIC / SAMPLE STATE / NOT DEVICE", 14, bitmap.getHeight() - 10, paint);
        File directory = new File("build/reports/qself-visual");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream stream = new FileOutputStream(new File(directory, name + ".jpg"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream));
        }
        bitmap.recycle();
    }

    @Test public void homeLightAndDarkRenderWithoutClippedLabels() throws Exception {
        for (boolean dark : new boolean[]{false, true}) {
            SettingsHomeView view = home(context(dark, 1f, 412, false), dark, 1, new ArrayList<>());
            layout(view, 412);
            verifyTextBounds(view);
            render(dark ? "home-dark" : "home-light", view);
        }
    }

    @Test public void narrowLargeTextUsesOneColumnAndRemainsScrollable() throws Exception {
        SettingsHomeView view = home(context(false, 2f, 320, false), true, 2, new ArrayList<>());
        layout(view, 320);
        verifyTextBounds(view);
        View appearance = view.findViewWithTag("appearance");
        View chat = view.findViewWithTag("chat");
        assertNotSame(appearance.getParent(), chat.getParent());
        assertTrue(view.getHeight() > 915);
        render("home-large-text", view);
    }

    @Test public void everyHomeActionIsClickableNamedAndAtLeast48dp() {
        List<String> clicked = new ArrayList<>();
        SettingsHomeView view = home(context(false, 1f, 412, false), false, 1, clicked);
        layout(view, 412);
        List<String> expected = new ArrayList<>();
        for (HomeCatalog.Section section : HomeCatalog.sections) expected.add(section.getId());
        java.util.Collections.addAll(expected, HomeCatalog.SEARCH, HomeCatalog.DIAGNOSTICS,
            HomeCatalog.THEME, HomeCatalog.BACKUP, HomeCatalog.CATALOG, HomeCatalog.ABOUT);
        for (String id : expected) {
            View button = view.findViewWithTag(id);
            assertNotNull(id, button);
            assertTrue(id, button.isFocusable());
            assertTrue(id, button.getHeight() >= SettingsVisuals.INSTANCE.dp(view.getContext(), 48));
            assertNotNull(id, button.getContentDescription());
            assertTrue(id, button.performClick());
        }
        assertEquals(expected, clicked);
    }

    @Test public void rebindReplacesClickHandlersAndStateWithoutDuplicatingViews() {
        List<String> oldClicks = new ArrayList<>(), newClicks = new ArrayList<>();
        SettingsHomeView view = home(context(false, 1f, 412, false), false, 1, oldClicks);
        int count = view.getChildCount();
        view.bind(new SettingsHomeView.State("QQ 9.2.10", true, true), 0,
            id -> { newClicks.add(id); return Unit.INSTANCE; });
        assertEquals(count, view.getChildCount());
        View diagnostics = view.findViewWithTag(HomeCatalog.DIAGNOSTICS);
        assertTrue(diagnostics.getContentDescription().toString().contains("记录开关已开"));
        diagnostics.performClick();
        assertTrue(oldClicks.isEmpty());
        assertEquals(java.util.Collections.singletonList(HomeCatalog.DIAGNOSTICS), newClicks);
    }

    @Test public void realSwitchCellGrowsWithTextAndKeepsSwitchHitTarget() {
        Context context = context(false, 2f, 320, false);
        TitleValueCell cell = new TitleValueCell(context);
        cell.setTitle("This feature has a longer title");
        cell.setSummary("A multiline summary must stay below the title and never overlap the native switch.");
        cell.setChecked(true);
        layout(cell, 288);
        verifyTextBounds(cell);
        assertTrue(cell.getSummaryView().getTop() >= cell.getTitleView().getBottom());
        assertTrue(cell.isClickOnSwitch(cell.getSwitchView().getLeft()));
        assertFalse(cell.isClickOnSwitch(0));
        assertEquals(cell.getTitle(), cell.getSwitchView().getContentDescription());
        cell.setHasError(true);
        cell.draw(new Canvas(Bitmap.createBitmap(288, cell.getHeight(), Bitmap.Config.ARGB_8888)));
    }

    @Test public void rtlTrailingControlStaysOnTheLeft() {
        TitleValueCell cell = new TitleValueCell(context(true, 1f, 412, true));
        cell.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        cell.setTitle("RTL feature");
        cell.setChecked(false);
        layout(cell, 380);
        assertTrue(cell.getSwitchView().getRight() < 190);
        assertTrue(cell.isClickOnSwitch(cell.getSwitchView().getLeft()));
    }

    @Test public void catalogReferencesOnlyExistingAnnotatedProvidersAndNoDuplicates() throws Exception {
        Path root = Paths.get("src/main/java");
        if (!Files.isDirectory(root)) root = Paths.get("app/src/main/java");
        Set<String> ids = new HashSet<>(), features = new HashSet<>();
        for (HomeCatalog.Section section : HomeCatalog.sections) {
            assertTrue(ids.add(section.getId()));
            assertFalse(section.getFeatures().isEmpty());
            for (String feature : section.getFeatures()) {
                assertTrue("duplicate " + feature, features.add(feature));
                Path file = root.resolve(feature.replace('.', '/') + ".kt");
                if (!Files.exists(file)) file = root.resolve(feature.replace('.', '/') + ".java");
                assertTrue("missing " + feature, Files.exists(file));
                assertTrue("not registered " + feature, new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8).contains("@UiItemAgentEntry"));
            }
        }
    }
}

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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.view.LayoutInflater;
import androidx.recyclerview.widget.RecyclerView;
import io.github.qauxv.R;
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
import org.robolectric.annotation.ResourcesMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, manifest = Config.NONE, qualifiers = "w412dp-h915dp-mdpi")
// AOSP resource parsing preserves the production 0x39 resource package ID.
// Robolectric's older Java ARSC parser crashes on its empty library chunk.
@ResourcesMode(ResourcesMode.Mode.NATIVE)
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
            io.github.qauxv.R.style.AppTheme_Ftb);
        context.getTheme().applyStyle(io.github.qauxv.R.style.Theme_Qself_Expressive, true);
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

    private Bitmap drawHardware(int width, int height, java.util.function.Consumer<Canvas> draw) {
        android.graphics.RenderNode node = new android.graphics.RenderNode("settings-test");
        node.setPosition(0, 0, width, height);
        Canvas recording = node.beginRecording(width, height);
        assertTrue("Optical tests must exercise the accelerated path", recording.isHardwareAccelerated());
        try { draw.accept(recording); } finally { node.endRecording(); }
        android.graphics.HardwareRenderer renderer = new android.graphics.HardwareRenderer();
        try (android.media.ImageReader reader = android.media.ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 1)) {
            android.view.Surface surface = reader.getSurface();
            try {
                renderer.setSurface(surface); renderer.setContentRoot(node);
                renderer.setLightSourceGeometry(0, 0, 0, 0); renderer.setLightSourceAlpha(0, 0);
                renderer.createRenderRequest().syncAndDraw();
                try (android.media.Image image = reader.acquireNextImage()) {
                    assertNotNull("No hardware render output", image);
                    android.media.Image.Plane plane = image.getPlanes()[0];
                    assertEquals(4, plane.getPixelStride());
                    java.nio.ByteBuffer packed = java.nio.ByteBuffer.allocate(width*height*4);
                    java.nio.ByteBuffer source = plane.getBuffer();
                    for (int y = 0; y < height; y++) {
                        java.nio.ByteBuffer row = source.duplicate();
                        row.position(y*plane.getRowStride()); row.limit(y*plane.getRowStride()+width*4);
                        packed.put(row);
                    }
                    packed.rewind();
                    Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    result.copyPixelsFromBuffer(packed);
                    return result;
                }
            } finally { renderer.destroy(); surface.release(); node.discardDisplayList(); }
        }
    }

    private void render(String name, View view) throws Exception {
        Bitmap bitmap = drawHardware(view.getWidth(), view.getHeight() + 28, view::draw);
        Canvas canvas = new Canvas(bitmap);
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

    private void renderPreviewPair(View light, View dark) throws Exception {
        // CI permits ten notices per step and truncates each message at 4096 characters.
        // Keep one real-view contact sheet within nine small base64 chunks, plus test totals.
        float scale = 720f / (light.getWidth() + dark.getWidth());
        int contentHeight = Math.round(Math.max(light.getHeight(), dark.getHeight()) * scale);
        Bitmap bitmap = drawHardware(720, contentHeight + 28, canvas -> {
            canvas.drawColor(Color.rgb(235, 239, 246));
            canvas.save(); canvas.scale(scale, scale);
            light.draw(canvas); canvas.translate(light.getWidth(), 0); dark.draw(canvas);
            canvas.restore();
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.DKGRAY); paint.setTextSize(10);
            canvas.drawText("REAL HOST LAYOUT / RECYCLERVIEW / MD3 EXPRESSIVE / TEST DATA / NOT DEVICE", 14, contentHeight + 18, paint);
        });
        byte[] result = null;
        for (int quality = 75; quality >= 5; quality -= 5) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            assertTrue(bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, bytes));
            result = bytes.toByteArray();
            if (result.length <= 24000) break;
        }
        assertNotNull(result);
        assertTrue("CI contact sheet exceeds annotation budget", result.length <= 24000);
        Files.write(Paths.get("build/reports/qself-visual/home-pair.webp"), result);
        bitmap.recycle();
    }

    /** Inflate the actual host XML and use the SAME item factory and list container as production. */
    private static class NativePage {
        FrameLayout host;
        SettingsListLayout list;
        SettingsHomeItem item;
        SettingsHomeView home() { return (SettingsHomeView) list.getRecycler().getChildAt(0); }
    }

    private NativePage page(Context context, int width, int height, List<String> clicks) {
        NativePage page = new NativePage();
        page.host = (FrameLayout) LayoutInflater.from(context).inflate(R.layout.activity_settings_ui_host, null, false);
        page.host.setBackground(SettingsVisuals.backdrop(SettingsVisuals.palette(context, 1)));
        com.google.android.material.appbar.MaterialToolbar toolbar = page.host.findViewById(R.id.topAppBar);
        toolbar.setTitle("设置");
        page.list = new SettingsListLayout(context);
        page.list.setBackground(SettingsVisuals.backdrop(SettingsVisuals.palette(context, 1)));
        ((FrameLayout) page.host.findViewById(R.id.fragment_container)).addView(page.list);
        page.item = new SettingsHomeItem(() -> new SettingsHomeView.State("QQ 9.2.10", false, false),
            () -> 1, id -> { clicks.add(id); return Unit.INSTANCE; });
        page.list.getRecycler().setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
                return page.item.createViewHolder(context, parent);
            }
            @Override public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
                page.item.bindView(holder, position, context);
            }
            @Override public int getItemCount() { return 1; }
        });
        page.list.getRecycler().setPadding(0, SettingsVisuals.INSTANCE.dp(context, 64), 0, SettingsVisuals.INSTANCE.dp(context, 24));
        measurePage(page, width, height, View.MeasureSpec.EXACTLY);
        return page;
    }

    private void measurePage(NativePage page, int width, int height, int mode) {
        page.host.measure(View.MeasureSpec.makeMeasureSpec(width, mode), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        page.host.layout(0, 0, page.host.getMeasuredWidth(), height);
    }

    private void verifyContainedChildren(View view) {
        if (!(view instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) view;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child.getVisibility() == View.GONE) continue;
            assertTrue("child outside horizontal bounds: " + child, child.getLeft() >= 0 && child.getRight() <= parent.getWidth());
            assertTrue("child outside vertical bounds: " + child, child.getTop() >= 0 && child.getBottom() <= parent.getHeight());
            verifyContainedChildren(child);
        }
    }

    @Test public void homeLightAndDarkRenderWithoutClippedLabels() throws Exception {
        List<View> frames = new ArrayList<>();
        for (boolean dark : new boolean[]{false, true}) {
            NativePage page = page(context(dark, 1f, 412, false), 412, 915, new ArrayList<>());
            assertEquals(412, page.home().getWidth());
            verifyTextBounds(page.home()); verifyContainedChildren(page.home());
            render(dark ? "home-dark" : "home-light", page.host);
            frames.add(page.host);
        }
        renderPreviewPair(frames.get(0), frames.get(1));
    }

    @Test public void atMostPassReproducesOldShrinkWrapAndFixedHomeFillsConstraint() {
        Context context = context(false, 1f, 412, false);
        SettingsHomeView oldContent = home(context, false, 1, new ArrayList<>());
        LinearLayout legacy = new LinearLayout(context);
        legacy.setOrientation(LinearLayout.VERTICAL);
        legacy.setPadding(oldContent.getPaddingLeft(), oldContent.getPaddingTop(), oldContent.getPaddingRight(), oldContent.getPaddingBottom());
        // Same children, same layout params, original LinearLayout.onMeasure behaviour.
        while (oldContent.getChildCount() > 0) {
            View child = oldContent.getChildAt(0); oldContent.removeView(child); legacy.addView(child);
        }
        int widthSpec = View.MeasureSpec.makeMeasureSpec(412, View.MeasureSpec.AT_MOST);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        legacy.measure(widthSpec, heightSpec);
        assertTrue("old AT_MOST shrink-wrap must be reproduced", legacy.getMeasuredWidth() < 412);
        SettingsHomeView fixed = home(context, false, 1, new ArrayList<>());
        fixed.measure(widthSpec, heightSpec); fixed.layout(0, 0, fixed.getMeasuredWidth(), fixed.getMeasuredHeight());
        assertEquals(412, fixed.getMeasuredWidth());
        verifyTextBounds(fixed); verifyContainedChildren(fixed);
    }

    @Test public void realRecyclerMeasurementHandlesWidthsFontsAndRepeatPasses() {
        for (int width : new int[]{320, 360, 412, 480}) for (float font : new float[]{1f, 1.3f, 2f}) {
            // Deliberately keep screenWidthDp=412: the actual pane, not device metrics, decides columns.
            NativePage page = page(context(false, font, 412, false), width, 915, new ArrayList<>());
            for (int mode : new int[]{View.MeasureSpec.AT_MOST, View.MeasureSpec.EXACTLY}) {
                measurePage(page, width, 915, mode);
                assertEquals(width, page.list.getWidth());
                assertEquals(width, page.list.getRecycler().getWidth());
                assertEquals(width, page.home().getWidth());
                View search = page.home().findViewWithTag(HomeCatalog.SEARCH);
                assertEquals(width-page.home().getPaddingLeft()-page.home().getPaddingRight(), search.getWidth());
                verifyTextBounds(page.home()); verifyContainedChildren(page.home());
                View people = page.home().findViewWithTag("people");
                View tools = page.home().findViewWithTag("tools");
                if (people.getParent() == tools.getParent()) assertEquals(people.getHeight(), tools.getHeight());
            }
        }
    }

    @Test public void paneResizeReflowsColumnsAndKeepsRealItemActions() {
        List<String> clicks = new ArrayList<>();
        NativePage page = page(context(false, 1f, 412, false), 412, 915, clicks);
        assertSame(page.home().findViewWithTag("appearance").getParent(), page.home().findViewWithTag("chat").getParent());
        measurePage(page, 320, 915, View.MeasureSpec.EXACTLY);
        assertNotSame(page.home().findViewWithTag("appearance").getParent(), page.home().findViewWithTag("chat").getParent());
        verifyTextBounds(page.home()); verifyContainedChildren(page.home());
        page.home().findViewWithTag(HomeCatalog.SEARCH).performClick();
        assertEquals(java.util.Collections.singletonList(HomeCatalog.SEARCH), clicks);
        measurePage(page, 480, 915, View.MeasureSpec.EXACTLY);
        assertSame(page.home().findViewWithTag("appearance").getParent(), page.home().findViewWithTag("chat").getParent());
        assertEquals(480, page.home().getWidth());
    }

    @Test public void realRecyclerCanReachFooterWithoutClippingAndRestoreTop() {
        NativePage page = page(context(false, 2f, 412, false), 320, 915, new ArrayList<>());
        RecyclerView recycler = page.list.getRecycler();
        recycler.scrollBy(0, 100000);
        assertFalse(recycler.canScrollVertically(1));
        View about = page.home().findViewWithTag(HomeCatalog.ABOUT);
        android.graphics.Rect rect = new android.graphics.Rect(0, 0, about.getWidth(), about.getHeight());
        recycler.offsetDescendantRectToMyCoords(about, rect);
        assertTrue(rect.bottom <= recycler.getHeight()-recycler.getPaddingBottom());
        assertTrue(rect.top >= recycler.getPaddingTop());
        recycler.scrollBy(0, -100000);
        assertEquals(recycler.getPaddingTop(), page.home().getTop());
    }

    @Test public void opticalGlassCompilesAndProducesRefractionNotFlatTransparency() throws Exception {
        for (String fieldName : new String[]{"BACKGROUND", "LENS"}) {
            java.lang.reflect.Field field = SettingsGlass.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            new android.graphics.RuntimeShader((String) field.get(null));
        }
        Context context = context(false, 1f, 412, false);
        android.graphics.drawable.Drawable glass = SettingsGlass.INSTANCE.material(context, SettingsVisuals.palette(context, 1), 24, null, new android.graphics.drawable.ColorDrawable(Color.WHITE));
        assertTrue(SettingsGlass.INSTANCE.isOptical(glass));
        glass.setBounds(0, 0, 300, 110);
        Bitmap bitmap = drawHardware(300, 110, glass::draw);
        assertNotEquals(bitmap.getPixel(150, 1), bitmap.getPixel(150, 20));
        assertNotEquals(bitmap.getPixel(30, 55), bitmap.getPixel(270, 55));
        android.graphics.drawable.Drawable solid = SettingsVisuals.surface(context, SettingsVisuals.palette(context, 2), 24, false);
        assertFalse(SettingsGlass.INSTANCE.isOptical(solid));
        bitmap.recycle();
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

    @Test public void sameStateRefreshPreservesFocusTargetsAndUpdatesDispatch() {
        List<String> oldClicks = new ArrayList<>(), newClicks = new ArrayList<>();
        SettingsHomeView view = home(context(false, 1f, 412, false), false, 1, oldClicks);
        View search = view.findViewWithTag(HomeCatalog.SEARCH);
        view.bind(new SettingsHomeView.State("QQ 9.2.10", false, false), 1,
            id -> { newClicks.add(id); return Unit.INSTANCE; });
        assertSame(search, view.findViewWithTag(HomeCatalog.SEARCH));
        search.performClick();
        assertTrue(oldClicks.isEmpty());
        assertEquals(java.util.Collections.singletonList(HomeCatalog.SEARCH), newClicks);
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

    @Test public void glassBackdropDoesNotPaintOutsideItsBounds() {
        android.graphics.drawable.Drawable backdrop = SettingsVisuals.backdrop(SettingsVisuals.palette(context(false, 1f, 412, false), 1));
        backdrop.setBounds(10, 10, 30, 30);
        Bitmap bitmap = drawHardware(40, 40, canvas -> { canvas.drawColor(Color.MAGENTA); backdrop.draw(canvas); });
        assertEquals(Color.MAGENTA, bitmap.getPixel(0, 0));
        assertEquals(Color.MAGENTA, bitmap.getPixel(39, 39));
        assertNotEquals(Color.MAGENTA, bitmap.getPixel(20, 20));
        bitmap.recycle();
    }

    @Test public void softwareCaptureFallsBackWithoutCrashing() {
        Context context = context(false, 1f, 412, false);
        Bitmap bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888);
        Canvas software = new Canvas(bitmap);
        android.graphics.drawable.Drawable backdrop = SettingsVisuals.backdrop(SettingsVisuals.palette(context, 1));
        backdrop.setBounds(0, 0, 200, 100); backdrop.draw(software);
        android.graphics.drawable.Drawable material = SettingsVisuals.surface(context, SettingsVisuals.palette(context, 1), 24, false);
        material.setBounds(0, 0, 200, 100); material.draw(software);
        assertNotEquals(0, bitmap.getPixel(100, 50)); bitmap.recycle();
    }

    @Test public void themeAccentMaintainsTextContrastEvenForWhiteYellowAndBlack() {
        for (boolean dark : new boolean[]{false, true}) {
            int backdrop = dark ? Color.rgb(48, 60, 82) : Color.rgb(203, 222, 255);
            for (int color : new int[]{Color.WHITE, Color.BLACK, Color.YELLOW, Color.CYAN, Color.RED, Color.BLUE}) {
                int foreground = SettingsVisuals.readableAccent(color, dark);
                assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(foreground, backdrop) >= 4.5);
            }
        }
    }

    @Test public void actualFeatureCellsRenderWithNativeSwitches() throws Exception {
        Context context = context(false, 1f, 412, false);
        SettingsVisuals.Palette palette = SettingsVisuals.palette(context, 1);
        android.widget.LinearLayout list = new android.widget.LinearLayout(context);
        list.setOrientation(android.widget.LinearLayout.VERTICAL);
        list.setPadding(16, 24, 16, 24);
        list.setBackground(SettingsVisuals.backdrop(palette));
        TextView heading = new TextView(context);
        heading.setText("外观"); heading.setTextSize(24); heading.setTextColor(palette.getText());
        list.addView(heading);
        String[][] rows = {{"液态玻璃底栏", "底栏的通透效果与形态"}, {"头像圆角（聊天）", "按自己的习惯调整圆角"},
            {"广告净化（总开关）", "只在需要时开启，不自动改变其他功能"}};
        for (String[] row : rows) {
            TitleValueCell cell = new TitleValueCell(context);
            cell.setTitle(row[0]); cell.setSummary(row[1]); cell.setChecked(false); cell.setHasDivider(false);
            cell.getTitleView().setTextColor(palette.getText()); cell.getSummaryView().setTextColor(palette.getSecondary());
            cell.setBackground(SettingsVisuals.surface(context, palette, 20, true));
            android.widget.LinearLayout.LayoutParams params = new android.widget.LinearLayout.LayoutParams(-1, -2);
            params.topMargin = 12; list.addView(cell, params);
        }
        layout(list, 412); verifyTextBounds(list); render("feature-cells", list);
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
                Path file = root.resolve(feature.split("\\$")[0].replace('.', '/') + ".kt");
                if (!Files.exists(file)) file = root.resolve(feature.split("\\$")[0].replace('.', '/') + ".java");
                assertTrue("missing " + feature, Files.exists(file));
                assertTrue("not registered " + feature, new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8).contains("UiItemAgentEntry"));
            }
        }
    }
    @Test public void ordinaryPagesAreOpaqueMaterialRegardlessOfLegacyGlassMode() {
        for (boolean dark : new boolean[]{false, true}) {
            Context context = context(dark, 1f, 412, false);
            for (int mode = 0; mode < 3; mode++) {
                SettingsVisuals.Palette p = SettingsVisuals.palette(context, mode);
                assertFalse(SettingsGlass.INSTANCE.isOptical(SettingsVisuals.backdrop(p)));
                assertTrue(SettingsVisuals.surface(context, p, 24, false) instanceof com.google.android.material.shape.MaterialShapeDrawable);
                assertEquals(255, Color.alpha(p.getSurface()));
                assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(p.getText(), p.getSurface()) >= 4.5);
                assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(p.getOnContainer(), p.getContainer()) >= 4.5);
            }
            SettingsHomeView view = home(context, false, 0, new ArrayList<>());
            layout(view, 412);
            assertTrue(view.findViewWithTag("appearance") instanceof com.google.android.material.card.MaterialCardView);
            assertTrue(new TitleValueCell(context).getSwitchView() instanceof com.google.android.material.materialswitch.MaterialSwitch);
        }
    }

    @Test public void migrationPreservesEveryFeatureKeyAndIsIdempotent() {
        android.content.SharedPreferences config = RuntimeEnvironment.getApplication().getSharedPreferences("profile-migration", 0);
        config.edit().clear().putBoolean("rq_risk_report_interceptor.enabled", true)
            .putBoolean("ForcePadMode.enabled", true).putBoolean("HideQZoneAD.enabled", true)
            .putBoolean("HideMiniAppLoadingAd.enabled", false).putString("rq_group_admin_marks", "test-fixture")
            .putInt("qself.settings.glass", 0).apply();
        java.util.Map<String, ?> original = config.getAll();
        sumicya.qself.profile.ProfileMigration.migrate(config);
        for (String key : original.keySet()) assertEquals(original.get(key), config.getAll().get(key));
        assertEquals(0, config.getInt("qself.overlays.glass", -1));
        assertEquals(1, config.getInt("qself.profile.schema", -1));
        java.util.Map<String, ?> first = config.getAll();
        sumicya.qself.profile.ProfileMigration.migrate(config);
        assertEquals(first, config.getAll());
    }

    @Test public void restoredBackupMigratesWithoutOverwritingNewOverlayPreference() {
        android.content.SharedPreferences config = RuntimeEnvironment.getApplication().getSharedPreferences("profile-restore", 0);
        config.edit().clear().putInt("qself.settings.glass", 0).putInt("qself.overlays.glass", 2).apply();
        sumicya.qself.profile.ProfileMigration.migrate(config);
        assertEquals(2, config.getInt("qself.overlays.glass", -1));
        config.edit().clear().putInt("qself.settings.glass", 99).apply();
        sumicya.qself.profile.ProfileMigration.migrate(config);
        assertEquals(1, config.getInt("qself.overlays.glass", -1));
        assertEquals(99, config.getInt("qself.settings.glass", -1));
        config.edit().clear().putString("qself.settings.glass", "malformed-test-value").apply();
        sumicya.qself.profile.ProfileMigration.migrate(config);
        assertEquals(1, config.getInt("qself.overlays.glass", -1));
        assertEquals("malformed-test-value", config.getString("qself.settings.glass", ""));
        config.edit().clear().putInt("qself.profile.schema", 3).apply();
        sumicya.qself.profile.ProfileMigration.migrate(config);
        assertEquals(3, config.getInt("qself.profile.schema", -1));
        assertFalse(config.contains("qself.overlays.glass"));
    }

    @Test public void runtimePolicyBlocksDormantClassNamesAndNestedCallbacks() {
        assertFalse(sumicya.qself.profile.SimplifiedProfile.isAllowedClass("sumicya.qself.feature.device.ForcePadMode"));
        assertFalse(sumicya.qself.profile.SimplifiedProfile.isAllowedClass("sumicya.qself.feature.device.ForcePadMode$Callback"));
        assertTrue(sumicya.qself.profile.SimplifiedProfile.isAllowedClass("sumicya.qself.feature.device.RiskReportInterceptor"));
        assertTrue(sumicya.qself.profile.SimplifiedProfile.isAllowedClass(HomeCatalog.DIAGNOSTICS));
        assertTrue(sumicya.qself.profile.SimplifiedProfile.dormantEntryCount() > 100);
        for (HomeCatalog.Section section : HomeCatalog.sections) {
            for (String feature : section.getFeatures()) assertTrue(sumicya.qself.profile.SimplifiedProfile.isAllowedClass(feature));
        }
    }

    @Test public void actualGeneratedRegistriesOnlyImportInventoryMembers() throws Exception {
        Path project = Paths.get(".");
        if (!Files.isDirectory(project.resolve("src/main/java"))) project = project.resolve("app");
        Path inventory = project.resolve("../config/feature-catalog.tsv");
        Set<String> allowed = new HashSet<>();
        for (String line : Files.readAllLines(inventory)) if (!line.startsWith("#") && !line.trim().isEmpty()) allowed.add(line.split("\t", -1)[5]);
        for (String registry : new String[]{"AnnotatedFunctionHookEntryList", "AnnotatedUiItemAgentEntryList"}) {
            String generated = Files.readString(project.resolve("build/generated/ksp/debug/kotlin/io/github/qauxv/gen/" + registry + ".kt"));
            assertFalse(generated.contains("ForcePadMode"));
            assertFalse(generated.contains("ExternalModuleConfigHook"));
            String annotation = registry.contains("FunctionHook") ? "FunctionHookEntry" : "UiItemAgentEntry";
            for (String name : allowed) {
                Path source = project.resolve("src/main/java/" + name.split("\\$")[0].replace('.', '/') + ".kt");
                if (!Files.exists(source)) source = project.resolve("src/main/java/" + name.split("\\$")[0].replace('.', '/') + ".java");
                String text = Files.readString(source);
                boolean annotated = text.contains("@" + annotation) || java.util.regex.Pattern
                    .compile("@\\[[^\\]]*\\b" + annotation + "\\b").matcher(text).find();
                if (annotated) assertTrue(name, generated.contains("import " + name.replace('$', '.') + "\n"));
            }
            java.util.regex.Matcher imports = java.util.regex.Pattern.compile("(?m)^import ([a-zA-Z0-9_.]+)$").matcher(generated);
            while (imports.find()) {
                String name = imports.group(1);
                if (!name.startsWith("kotlin.") && !name.startsWith("io.github.qauxv.base.")) assertTrue(name, allowed.stream().anyMatch(id -> id.replace('$', '.').equals(name)));
            }
        }
    }

}

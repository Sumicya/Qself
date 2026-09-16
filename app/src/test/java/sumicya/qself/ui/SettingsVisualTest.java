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
        SettingsDynamicColors.apply(context);
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

    private void renderPreviewPair(View light, View dark) throws Exception { renderPreviewPair(light, dark, "home-pair"); }

    private void renderPreviewPair(View light, View dark, String name) throws Exception {
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
        Files.createDirectories(Paths.get("build/reports/qself-visual"));
        Files.write(Paths.get("build/reports/qself-visual/" + name + ".webp"), result);
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

    private SettingsAccordion findNestedAccordion(View view) {
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup parent = (ViewGroup) view;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof SettingsAccordion) return (SettingsAccordion) child;
            SettingsAccordion nested = findNestedAccordion(child);
            if (nested != null) return nested;
        }
        return null;
    }

    private SettingsAccordion nearestAccordion(View view) {
        View parent = view.getParent() instanceof View ? (View) view.getParent() : null;
        while (parent != null) {
            if (parent instanceof SettingsAccordion) return (SettingsAccordion) parent;
            parent = parent.getParent() instanceof View ? (View) parent.getParent() : null;
        }
        return null;
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
        // The redesigned cards legitimately fill the offered width; only the
        // fixed home view must never shrink below it.
        assertTrue(legacy.getMeasuredWidth() <= 412);
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
                assertNotNull("Home header offers an in-content search entry", page.home().findViewWithTag(HomeCatalog.SEARCH));
                verifyTextBounds(page.home()); verifyContainedChildren(page.home());
                View people = page.home().findViewWithTag("people");
                View tools = page.home().findViewWithTag("tools");
                if (people.getParent() == tools.getParent()) assertEquals(people.getHeight(), tools.getHeight());
            }
        }
    }

    @Test public void paneResizeKeepsFullWidthAccordionsAndRealItemActions() {
        List<String> clicks = new ArrayList<>();
        NativePage page = page(context(false, 1f, 412, false), 412, 915, clicks);
        assertNotSame(page.home().findViewWithTag("appearance").getParent(), page.home().findViewWithTag("chat").getParent());
        measurePage(page, 320, 915, View.MeasureSpec.EXACTLY);
        assertNotSame(page.home().findViewWithTag("appearance").getParent(), page.home().findViewWithTag("chat").getParent());
        verifyTextBounds(page.home()); verifyContainedChildren(page.home());
        page.home().findViewWithTag(HomeCatalog.CATALOG).performClick();
        assertEquals(java.util.Collections.singletonList(HomeCatalog.CATALOG), clicks);
        measurePage(page, 480, 915, View.MeasureSpec.EXACTLY);
        assertNotSame(page.home().findViewWithTag("appearance").getParent(), page.home().findViewWithTag("chat").getParent());
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

    @Test public void liveGlassRespondsToActualSourcePixels() throws Exception {
        java.lang.reflect.Field field = SettingsGlass.class.getDeclaredField("LENS");
        field.setAccessible(true);
        new android.graphics.RuntimeShader((String) field.get(null));
        Context context = context(false, 1f, 412, false);
        View source = new View(context);
        source.setBackgroundColor(Color.RED);
        source.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(110, View.MeasureSpec.EXACTLY));
        source.layout(0, 0, 300, 110);
        android.graphics.drawable.Drawable glass = SettingsGlass.INSTANCE.material(context, SettingsVisuals.palette(context, 0), 24, null,
            new android.graphics.drawable.ColorDrawable(Color.WHITE), source);
        assertTrue(SettingsGlass.INSTANCE.isOptical(glass));
        glass.setBounds(0, 0, 300, 110);
        Bitmap first = drawHardware(300, 110, glass::draw);
        assertTrue("Real source must be red", Color.red(first.getPixel(150, 55)) > Color.blue(first.getPixel(150, 55)) + 100);
        source.setBackgroundColor(Color.BLUE);
        Bitmap next = drawHardware(300, 110, glass::draw);
        assertTrue("Source updates must reach the lens", Color.blue(next.getPixel(150, 55)) > Color.red(next.getPixel(150, 55)) + 100);
        assertEquals(0, Color.alpha(next.getPixel(0, 0)));
        assertTrue("Must not silently fall back", SettingsGlass.INSTANCE.isOptical(glass));
        SettingsGlass.INSTANCE.deform(glass, 1f);
        Bitmap deformed = drawHardware(300, 110, glass::draw);
        assertTrue(Color.alpha(next.getPixel(7, 8)) > 0);
        assertEquals("Dragging changes the actual lens contour", 0, Color.alpha(deformed.getPixel(7, 8)));
        deformed.recycle();
        SettingsGlass.INSTANCE.dispose(glass); first.recycle(); next.recycle();
    }

    @Test public void lensBendsARealEdgeBeyondBlurAlone() {
        Context context = context(false, 1f, 412, false);
        View source = new View(context) {
            @Override protected void onDraw(Canvas canvas) {
                canvas.drawColor(Color.BLACK);
                Paint paint = new Paint(); paint.setColor(Color.RED);
                canvas.drawRect(8, 0, 300, 110, paint);
            }
        };
        source.layout(0, 0, 300, 110);
        android.graphics.drawable.Drawable lens = SettingsGlass.INSTANCE.material(context, SettingsVisuals.palette(context, 0), 24, null,
            new android.graphics.drawable.ColorDrawable(Color.WHITE), source);
        lens.setBounds(0, 0, 300, 110);
        android.graphics.RenderNode blur = new android.graphics.RenderNode("blur-only reference");
        blur.setPosition(0, 0, 300, 110);
        Canvas recording = blur.beginRecording(300, 110); source.draw(recording); blur.endRecording();
        blur.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(2f, 2f, android.graphics.Shader.TileMode.CLAMP));
        Bitmap reference = drawHardware(300, 110, c -> c.drawRenderNode(blur));
        Bitmap refracted = drawHardware(300, 110, lens::draw);
        assertTrue("A real edge must bend, not merely tint or blur", Color.red(refracted.getPixel(5, 55)) > Color.red(reference.getPixel(5, 55)) + 80);
        assertTrue(SettingsGlass.INSTANCE.isOptical(lens));
        SettingsGlass.INSTANCE.dispose(lens); blur.discardDisplayList(); reference.recycle(); refracted.recycle();
    }

    @Test public void checkedMotionDoesNotReplayOnBindingOrChangeStateTwice() throws Exception {
        org.robolectric.android.controller.ActivityController<android.app.Activity> controller =
            org.robolectric.Robolectric.buildActivity(android.app.Activity.class);
        android.app.Activity activity = controller.get();
        activity.setTheme(io.github.qauxv.R.style.Theme_Qself_Expressive);
        controller.setup();
        SquareStateControl control = new SquareStateControl(activity);
        activity.setContentView(control);
        layout(control, 48);
        assertTrue(control.isAttachedToWindow());
        assertTrue(SettingsMotion.INSTANCE.enabled());
        final int[] calls = {0};
        control.setOnCheckedChangeListener((button, checked) -> calls[0]++);
        control.setChecked(true);
        java.lang.reflect.Field field = SquareStateControl.class.getDeclaredField("animator"); field.setAccessible(true);
        android.animation.ValueAnimator animation = (android.animation.ValueAnimator) field.get(control);
        assertNotNull(animation);
        animation.setCurrentFraction(.5f);
        assertTrue(control.isChecked()); assertEquals(1, calls[0]);
        control.setCheckedWithoutAnimation(false);
        assertFalse(animation.isRunning()); assertFalse(control.isChecked()); assertEquals(2, calls[0]);
        controller.pause().stop().destroy();
    }

    @Test public void noSourceAndRecursiveSourceUseHonestSolidFallback() {
        Context context = context(false, 1f, 412, false);
        View source = new View(context);
        android.graphics.drawable.Drawable flat = SettingsVisuals.surface(context, SettingsVisuals.palette(context, 2), 24, false);
        assertSame(flat, SettingsGlass.INSTANCE.material(context, SettingsVisuals.palette(context, 0), 24, null, flat, null));
        assertSame(flat, SettingsGlass.INSTANCE.material(context, SettingsVisuals.palette(context, 0), 24, source, flat, source));
        assertFalse(SettingsGlass.INSTANCE.isOptical(flat));
    }

    @Test public void dynamicStateGlyphsStayLegibleThroughTheTransition() {
        for (boolean dark : new boolean[]{false, true}) {
            SettingsVisuals.Palette p = SettingsVisuals.palette(context(dark, 1f, 412, false), 2);
            for (int step = 0; step <= 20; step++) {
                float progress = step / 20f;
                int background = androidx.core.graphics.ColorUtils.blendARGB(p.getSurface(), p.getContainer(), .35f + .65f * progress);
                int candidate = androidx.core.graphics.ColorUtils.blendARGB(p.getText(), p.getOnContainer(), progress);
                int foreground = SettingsVisuals.stateForeground(candidate, background);
                assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(foreground, androidx.core.graphics.ColorUtils.setAlphaComponent(background, 255)) >= 3.0);
            }
        }
    }

    @Test public void settingsPrimaryUsesTheFrameworkWallpaperPalette() {
        for (boolean dark : new boolean[]{false, true}) {
            Context context = context(dark, 1f, 412, false);
            assertTrue(SettingsDynamicColors.apply(context));
            // API 34+ exposes semantic roles (including system contrast), not just palette tones.
            int expected = context.getColor(dark ? android.R.color.system_primary_dark : android.R.color.system_primary_light);
            assertEquals(expected, com.google.android.material.color.MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, 0));
            assertEquals(expected, SettingsVisuals.palette(context, 2).getAccent());
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
        java.util.Collections.addAll(expected, HomeCatalog.DIAGNOSTICS,
            HomeCatalog.THEME, HomeCatalog.BACKUP, HomeCatalog.CATALOG, HomeCatalog.ABOUT);
        for (String id : expected) {
            View button = view.findViewWithTag(id);
            assertNotNull(id, button);
            assertTrue(id, button.isFocusable());
            assertTrue(id, button.getHeight() >= SettingsVisuals.INSTANCE.dp(view.getContext(), 48));
            assertNotNull(id, button.getContentDescription());
            assertTrue(id, button.performClick());
        }
        expected.removeIf(id -> HomeCatalog.sections.stream().anyMatch(section -> section.getId().equals(id)));
        assertEquals(expected, clicked);
    }

    @Test public void homeExpansionDoesNotNavigateAndSurvivesRebindAndSavedState() throws Exception {
        List<String> clicks = new ArrayList<>();
        SettingsHomeView view = home(context(false, 1f, 412, false), false, 2, clicks);
        layout(view, 412);
        SettingsAccordion accordion = (SettingsAccordion) view.findViewWithTag("chat").getParent();
        assertFalse(accordion.getExpanded());
        accordion.setExpanded(true, false);
        layout(view, 412);
        assertTrue(clicks.isEmpty());
        // One real level: the section card hosts its rows directly, with no group card between
        // the section and its features (the retired second level).
        ViewGroup body = (ViewGroup) accordion.getChildAt(1);
        assertTrue("an expanded section card must have a body", body.getChildCount() > 0);
        assertNull("the section body must not nest another accordion", findNestedAccordion(body));
        assertNull("the retired group row must be gone", view.findViewWithTag("group:qself-messages"));
        // Feature rows come from the generated provider registry, which cannot be built off-device
        // (it constructs every catalog provider at once). When rows are there, each one must belong
        // to the card it is shown in - i.e. no row may sit behind another accordion.
        View row = view.findViewWithTag("cc.ioctl.hook.msg.ShowMsgCount");
        if (row != null) assertSame("a row belongs to the card it is shown in", accordion, nearestAccordion(row));
        view.bind(new SettingsHomeView.State("QQ 9.2.10", true, false), 2, id -> { clicks.add(id); return Unit.INSTANCE; });
        assertTrue(((SettingsAccordion) view.findViewWithTag("chat").getParent()).getExpanded());
        android.os.Parcelable saved = view.onSaveInstanceState();
        SettingsHomeView restored = home(view.getContext(), false, 2, clicks);
        restored.onRestoreInstanceState(saved);
        layout(restored, 412);
        assertTrue(((SettingsAccordion) restored.findViewWithTag("chat").getParent()).getExpanded());
        // A feature row opens its own settings; it is not a home navigation action.
        View restoredRow = restored.findViewWithTag("cc.ioctl.hook.msg.ShowMsgCount");
        if (restoredRow != null) {
            restoredRow.performClick();
            assertTrue("a feature row must not navigate the home", clicks.isEmpty());
        }
        verifyTextBounds(restored); verifyContainedChildren(restored);
        render("home-expanded", restored);
    }

    @Test public void levelStackUnwindsNewestFirstAndScopesCascadesToTheirContainer() {
        Context context = context(false, 1f, 412, false);
        SettingsAccordion card = new SettingsAccordion(context, "分类", "副标题", () -> new View(context));
        View panelInsideTheCard = new View(context);
        View panelOutsideTheCard = new View(context);
        SettingsLevelStack stack = new SettingsLevelStack();
        stack.push(card, null);
        stack.push(panelInsideTheCard, card);
        stack.push(panelOutsideTheCard, null);

        // Back closes the newest level first, whatever kind it is.
        assertSame(panelOutsideTheCard, stack.top());
        stack.remove(panelOutsideTheCard);
        assertSame(panelInsideTheCard, stack.top());

        // Collapsing a card takes exactly the levels opened inside that card.
        assertEquals(java.util.Collections.singletonList(panelInsideTheCard), stack.inside(card));
        assertTrue(stack.inside(panelOutsideTheCard).isEmpty());

        // Re-registering the same level (a card is re-attached when the home rebinds) is idempotent.
        stack.push(panelInsideTheCard, card);
        assertEquals(2, stack.getSize());
        stack.push(card, null);
        assertEquals(2, stack.getSize());

        stack.clear();
        assertTrue(stack.isEmpty());
        assertNull(stack.top());
    }

    @Test public void retiredWindowMaterialIsOpaqueRegardlessOfOldPreference() {
        for (boolean dark : new boolean[]{false, true}) {
            Context context = context(dark, 1f, 412, false);
            android.graphics.drawable.Drawable window = SettingsAppearanceItem.INSTANCE.windowMaterial(context, 12);
            window.setBounds(0, 0, 200, 100);
            Bitmap first = drawHardware(200, 100, c -> { c.drawColor(Color.BLUE); window.draw(c); });
            Bitmap second = drawHardware(200, 100, c -> { c.drawColor(Color.RED); window.draw(c); });
            assertEquals(first.getPixel(100, 50), second.getPixel(100, 50));
            assertEquals(255, window.getAlpha());
            first.recycle(); second.recycle();
        }
    }

    @Test public void rebindReplacesClickHandlersAndStateWithoutDuplicatingViews() {
        List<String> oldClicks = new ArrayList<>(), newClicks = new ArrayList<>();
        SettingsHomeView view = home(context(false, 1f, 412, false), false, 1, oldClicks);
        int count = view.getChildCount();
        view.bind(new SettingsHomeView.State("QQ 9.2.10", true, true), 0,
            id -> { newClicks.add(id); return Unit.INSTANCE; });
        assertEquals(count, view.getChildCount());
        View diagnostics = view.findViewWithTag(HomeCatalog.DIAGNOSTICS);
        assertTrue(diagnostics.getContentDescription().toString().contains("记录已开"));
        diagnostics.performClick();
        assertTrue(oldClicks.isEmpty());
        assertEquals(java.util.Collections.singletonList(HomeCatalog.DIAGNOSTICS), newClicks);
    }

    @Test public void sameStateRefreshPreservesFocusTargetsAndUpdatesDispatch() {
        List<String> oldClicks = new ArrayList<>(), newClicks = new ArrayList<>();
        SettingsHomeView view = home(context(false, 1f, 412, false), false, 1, oldClicks);
        View search = view.findViewWithTag(HomeCatalog.CATALOG);
        view.bind(new SettingsHomeView.State("QQ 9.2.10", false, false), 1,
            id -> { newClicks.add(id); return Unit.INSTANCE; });
        assertSame(search, view.findViewWithTag(HomeCatalog.CATALOG));
        search.performClick();
        assertTrue(oldClicks.isEmpty());
        assertEquals(java.util.Collections.singletonList(HomeCatalog.CATALOG), newClicks);
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
        // The slot is flush to the trailing edge; the whole row toggles.
        assertFalse(cell.isClickOnSwitch(0));
        assertTrue(cell.isClickOnSwitch(cell.getSwitchView().getLeft()));
        assertFalse(cell.isClickOnSwitch(cell.getSwitchView().getRight() + 1));
        assertEquals(288, cell.getSwitchView().getRight());
        assertEquals(cell.getTitle(), cell.getSwitchView().getContentDescription());
        cell.setHasError(true);
        cell.draw(new Canvas(Bitmap.createBitmap(288, cell.getHeight(), Bitmap.Config.ARGB_8888)));
    }

    @Test public void longDescriptionStaysOnOneLineAndRedundantOnesAreDropped() throws Exception {
        Context context = context(false, 1f, 412, false);
        TitleValueCell longText = new TitleValueCell(context);
        longText.setTitle("This feature has a longer title");
        longText.setSummary("A description long enough to be clamped: the row keeps the height of every other row, and the rest of this sentence is only in the accessibility text.");
        longText.setChecked(true);
        layout(longText, 380);
        TitleValueCell shortText = new TitleValueCell(context);
        shortText.setTitle("This feature has a longer title");
        shortText.setSummary("One line.");
        shortText.setChecked(true);
        layout(shortText, 380);
        assertEquals("a long description must not grow the row", shortText.getHeight(), longText.getHeight());
        assertEquals(1, longText.getSummaryView().getMaxLines());
        assertEquals("the clamped text must still be laid out", 1, longText.getSummaryView().getLayout().getLineCount());
        assertTrue("the clipped tail must be marked", longText.getSummaryView().getLayout().getEllipsisCount(0) > 0);
        verifyTextBounds(longText);

        // A description that only restates the title spends a line on nothing.
        assertTrue(io.github.qauxv.dsl.item.UiAgentItem.redundantSummary("隐藏小程序加载广告", "隐藏小程序加载广告功能"));
        assertTrue(io.github.qauxv.dsl.item.UiAgentItem.redundantSummary("消息防撤回", "消息防撤回"));
        assertTrue(io.github.qauxv.dsl.item.UiAgentItem.redundantSummary("底栏玻璃", null));
        assertFalse(io.github.qauxv.dsl.item.UiAgentItem.redundantSummary("特别关心通知", "QQ 特别关心好友私聊消息通知"));
        assertFalse(io.github.qauxv.dsl.item.UiAgentItem.redundantSummary("隐藏小程序加载广告", "未经测试，小程序白屏 30s 以上请关闭此功能"));
        render("row-slim", longText);
    }

    @Test public void rtlTrailingSlotGoesToTheLeadingSide() {
        TitleValueCell cell = new TitleValueCell(context(true, 1f, 412, true));
        cell.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        cell.setTitle("RTL feature"); cell.setChecked(false);
        layout(cell, 380);
        // Trailing in RTL is the visual leading (left) edge.
        assertEquals(0, cell.getSwitchView().getLeft());
        assertEquals(52, cell.getSwitchView().getRight());
        assertTrue(cell.isClickOnSwitch(0));
        assertFalse(cell.isClickOnSwitch(379));
    }

    @Test public void trailingSquareSlotPreservesCheckableSemantics() {
        TitleValueCell cell = new TitleValueCell(context(false, 1f, 412, false));
        cell.setTitle("\u72ec\u7acb\u5f00\u5173"); cell.setChecked(false);
        layout(cell, 380);
        // Trailing slot: a fixed 52dp square flush to the trailing card wall,
        // filling the whole 52dp row height so neighbouring slots merge.
        assertEquals(52, cell.getHeight());
        assertEquals(52, cell.getSwitchView().getWidth());
        assertEquals(52, cell.getSwitchView().getHeight());
        assertEquals(328, cell.getSwitchView().getLeft());
        assertEquals(380, cell.getSwitchView().getRight());
        assertEquals(0, cell.getSwitchView().getTop());
        assertTrue(cell.getSwitchView().getLeft() > ((View) cell.getTitleView().getParent()).getRight());
        final int[] changes = {0};
        cell.getSwitchView().setOnCheckedChangeListener((button, value) -> changes[0]++);
        cell.getSwitchView().toggle();
        assertTrue(cell.isChecked()); assertEquals(1, changes[0]);
        cell.setUnavailable(true);
        cell.getSwitchView().toggle();
        assertFalse(cell.isChecked()); assertEquals(2, changes[0]);
    }

    @Test public void explicitOverlayToneKeepsOpaqueReadableOptionSurfaces() {
        for (int tone : new int[]{1, 2}) {
            SettingsVisuals.Palette p = GlassAppearanceEditor.INSTANCE.palette(context(false, 1f, 412, false), tone, 1);
            assertEquals(tone == 2, p.getDark());
            assertEquals(255, Color.alpha(p.getSurface()));
            assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(p.getText(), p.getSurface()) >= 4.5);
        }
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
        List<View> frames = new ArrayList<>();
        for (boolean dark : new boolean[]{false, true}) {
            Context context = context(dark, 1f, 412, false);
            NativePage page = page(context, 412, 540, new ArrayList<>());
            ((com.google.android.material.appbar.MaterialToolbar) page.host.findViewById(R.id.topAppBar)).setTitle("选项 · MD3");
            String[][] data = {{"底部导航栏液态玻璃", "右侧状态槽；点击说明配置文字、数量与玻璃"},
                {"消息防撤回", "关闭这一项，不改变其他选项"}, {"版本不支持的功能", "当前不可用；保留原配置"},
                {"出现错误的功能", "查看功能错误记录，其他开关不受影响"},
                {"较长的说明单行省略", "超出的一行省略，完整文字保留在无障碍描述里；52dp 状态槽大小不变。"}};
            page.list.getRecycler().setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                @Override public int getItemCount() { return data.length; }
                @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
                    TitleValueCell cell = new TitleValueCell(context);
                    SettingsVisuals.INSTANCE.decorateRow(cell, context, true);
                    return new RecyclerView.ViewHolder(cell) {};
                }
                @Override public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
                    TitleValueCell cell = (TitleValueCell) holder.itemView;
                    cell.setTitle(data[position][0]); cell.setSummary(data[position][1]);
                    cell.setChecked(position == 0 || position == 3 || position == 4);
                    cell.setUnavailable(position == 2); cell.setHasError(position == 3);
                }
            });
            measurePage(page, 412, 540, View.MeasureSpec.EXACTLY);
            verifyTextBounds(page.host); verifyContainedChildren(page.host);
            render(dark ? "options-dark" : "options-light", page.host);
            frames.add(page.host);
        }
        renderPreviewPair(frames.get(0), frames.get(1), "options-pair");
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
            assertTrue(view.findViewWithTag("appearance").getParent() instanceof SettingsAccordion);
            assertTrue(new TitleValueCell(context).getSwitchView() instanceof SquareStateControl);
        }
    }

    @Test public void migrationPreservesEveryFeatureKeyAndIsIdempotent() {
        android.content.SharedPreferences config = RuntimeEnvironment.getApplication().getSharedPreferences("profile-migration", 0);
        config.edit().clear().putBoolean("rq_risk_report_interceptor.enabled", true)
            .putBoolean("ExternalModuleConfigHook.enabled", true).putBoolean("HideQZoneAD.enabled", true)
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
        assertFalse(sumicya.qself.profile.SimplifiedProfile.isAllowedClass("io.github.qauxv.chainloader.detail.ui.ExternalModuleConfigHook"));
        assertFalse(sumicya.qself.profile.SimplifiedProfile.isAllowedClass("io.github.qauxv.chainloader.detail.ui.ExternalModuleConfigHook$Callback"));
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
        Path catalog = project.resolve("../libs/ksp/src/main/kotlin/cn/lliiooll/processors/qself/QselfCatalog.kt");
        Set<String> allowed = new HashSet<>();
        for (String line : Files.readAllLines(catalog)) {
            int q1 = line.indexOf('"');
            int q2 = line.lastIndexOf('"');
            if (q1 >= 0 && q2 > q1) {
                String row = line.substring(q1 + 1, q2).replace("${'$'}", "$");
                if (row.split("\t", -1).length == 6) allowed.add(row.split("\t", -1)[5]);
            }
        }
        for (String registry : new String[]{"AnnotatedFunctionHookEntryList", "AnnotatedUiItemAgentEntryList"}) {
            String generated = Files.readString(project.resolve("build/generated/ksp/debug/kotlin/io/github/qauxv/gen/" + registry + ".kt"));
            assertFalse(generated.contains("ExternalModuleConfigHook"));
            assertFalse(generated.contains("ExternalModuleConfigHook"));
            String annotation = registry.contains("FunctionHook") ? "FunctionHookEntry" : "UiItemAgentEntry";
            for (String name : allowed) {
                Path source = project.resolve("src/main/java/" + name.split("\\$")[0].replace('.', '/') + ".kt");
                if (!Files.exists(source)) source = project.resolve("src/main/java/" + name.split("\\$")[0].replace('.', '/') + ".java");
                String text = Files.readString(source);
                boolean annotated = text.contains("@" + annotation) || java.util.regex.Pattern
                    .compile("@\\[[^\\]]*\\b" + annotation + "\\b").matcher(text).find();
                if (annotated) {
                    String canonical = name.replace('$', '.');
                    String outer = name.split("\\$")[0];
                    // KotlinPoet may import the enclosing class and qualify its nested entry.
                    boolean imported = generated.contains("import " + canonical + "\n") ||
                        (name.contains("$") && generated.contains("import " + outer + "\n") &&
                            generated.contains("." + name.substring(name.indexOf('$') + 1).replace('$', '.') + ".INSTANCE"));
                    assertTrue(name, imported);
                }
            }
            java.util.regex.Matcher imports = java.util.regex.Pattern.compile("(?m)^import ([a-zA-Z0-9_.]+)$").matcher(generated);
            while (imports.find()) {
                String name = imports.group(1);
                if (!name.startsWith("kotlin.") && !name.startsWith("io.github.qauxv.base.")) assertTrue(name, allowed.stream().anyMatch(id -> id.replace('$', '.').equals(name) || id.startsWith(name + "$")));
            }
        }
    }

}

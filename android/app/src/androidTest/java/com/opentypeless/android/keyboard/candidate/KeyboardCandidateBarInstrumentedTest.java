package com.opentypeless.android.keyboard.candidate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.opentypeless.android.R;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class KeyboardCandidateBarInstrumentedTest {
    @Test
    public void horizontalBarRendersNumberedAccessibleCandidatesAndStableSelection() {
        onMain(() -> {
            Harness harness = new Harness();
            CandidatePage page = page("rime", 9L, 17L, 0, 1, "你好", "你们", "你可以");

            assertTrue(harness.bar.showPage(page));
            assertEquals(View.VISIBLE, harness.bar.root().getVisibility());
            assertEquals(3, harness.bar.candidateRow().getChildCount());
            assertEquals("1 你好", harness.bar.candidateButton(0).getText().toString());
            assertTrue(harness.bar.candidateButton(0)
                    .getContentDescription().toString().contains("你好"));
            assertTrue(harness.bar.candidateButton(1).performClick());

            assertEquals(1, harness.selections.size());
            CandidatePage.Selection selected = harness.selections.get(0);
            assertEquals("rime", selected.producerId());
            assertEquals(17L, selected.pageRevision());
            assertEquals(1, selected.candidateIndex());
            assertEquals("你们", selected.expectedText());
        });
    }

    @Test
    public void pagingButtonsEmitOnlyAvailableDirectionWithOriginalIdentity() {
        onMain(() -> {
            Harness harness = new Harness();
            CandidatePage middle = page("latin", 3L, 4L, 1, 3, "there", "their");
            harness.bar.showPage(middle);

            assertEquals(View.VISIBLE, harness.bar.previousButton().getVisibility());
            assertEquals(View.VISIBLE, harness.bar.nextButton().getVisibility());
            assertTrue(harness.bar.previousButton().performClick());
            assertTrue(harness.bar.nextButton().performClick());

            assertEquals(2, harness.pageRequests.size());
            assertEquals(CandidatePage.Direction.PREVIOUS,
                    harness.pageRequests.get(0).direction());
            assertEquals(CandidatePage.Direction.NEXT,
                    harness.pageRequests.get(1).direction());
            assertEquals(4L, harness.pageRequests.get(1).pageRevision());
        });
    }

    @Test
    public void staleDetachedButtonCannotSelectAfterReplacementOrClear() {
        onMain(() -> {
            Harness harness = new Harness();
            harness.bar.showPage(page("latin", 1L, 1L, 0, 1, "old"));
            Button oldButton = harness.bar.candidateButton(0);
            harness.bar.showPage(page("rime", 1L, 2L, 0, 1, "new"));

            assertTrue(oldButton.performClick());
            assertEquals(0, harness.selections.size());
            Button newButton = harness.bar.candidateButton(0);
            harness.bar.clear();
            assertTrue(newButton.performClick());
            assertEquals(0, harness.selections.size());
            assertFalse(harness.bar.hasPage());
            assertEquals(View.GONE, harness.bar.root().getVisibility());
        });
    }

    @Test
    public void sensitivePolicyDestructivelyClearsPlaintextAndRejectsNewPage() {
        onMain(() -> {
            Harness harness = new Harness();
            harness.bar.showPage(page("rime", 1L, 1L, 0, 1, "secret"));
            harness.bar.setPlaintextVisible(false);

            assertFalse(harness.bar.hasPage());
            assertEquals(0, harness.bar.candidateRow().getChildCount());
            assertEquals(View.GONE, harness.bar.root().getVisibility());
            assertFalse(harness.bar.showPage(page("rime", 2L, 2L, 0, 1, "hidden")));
            assertEquals(0, harness.bar.candidateRow().getChildCount());

            harness.bar.setPlaintextVisible(true);
            assertFalse(harness.bar.hasPage());
            assertEquals(View.GONE, harness.bar.root().getVisibility());
        });
    }

    @Test
    public void disabledInteractionAndFortyEightDpTargetsRemainFailClosed() {
        onMain(() -> {
            Harness harness = new Harness();
            harness.bar.showPage(page("rime", 1L, 1L, 0, 2, "甲"));
            harness.bar.setInteractionEnabled(false);

            assertFalse(harness.bar.candidateButton(0).isEnabled());
            assertFalse(harness.bar.nextButton().isEnabled());
            int minimum = Math.round(KeyboardCandidateBar.MINIMUM_TOUCH_TARGET_DP
                    * harness.context.getResources().getDisplayMetrics().density);
            assertTrue(harness.bar.root().getMinimumHeight() >= minimum);
            assertTrue(harness.bar.candidateButton(0).getMinimumHeight() >= minimum);
            assertTrue(harness.bar.candidateButton(0).getMinimumWidth() >= minimum);
            assertEquals(0, harness.selections.size());
            assertEquals(0, harness.pageRequests.size());
        });
    }

    @Test
    public void latinAndRimePagesReuseTheSameViewWithoutRetainingOldText() {
        onMain(() -> {
            Harness harness = new Harness();
            harness.bar.showPage(page("latin", 1L, 1L, 0, 1, "hello", "help"));
            harness.bar.showPage(page("rime", 2L, 2L, 0, 1, "你好"));

            assertEquals(1, harness.bar.candidateRow().getChildCount());
            assertEquals("1 你好", harness.bar.candidateButton(0).getText().toString());
            assertFalse(harness.bar.candidateButton(0).getText().toString().contains("hello"));
        });
    }

    @Test
    public void candidateStripUsesIntegratedSurfaceWithoutFloatingKeyCards() {
        onMain(() -> {
            Harness harness = new Harness();
            harness.bar.showPage(page("rime", 2L, 3L, 0, 1, "行", "型"));

            ColorDrawable surface = (ColorDrawable) harness.bar.root().getBackground();
            assertEquals(harness.context.getColor(R.color.ime_surface), surface.getColor());
            StateListDrawable background = (StateListDrawable)
                    harness.bar.candidateButton(0).getBackground();
            GradientDrawable restingSurface = (GradientDrawable) background.getCurrent();
            assertEquals(Color.TRANSPARENT, restingSurface.getColor().getDefaultColor());
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams)
                    harness.bar.candidateButton(0).getLayoutParams();
            assertEquals(0, params.getMarginStart());
            assertEquals(0, params.getMarginEnd());
        });
    }

    private static void onMain(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }

    private static CandidatePage page(
            String producer,
            long generation,
            long revision,
            int pageIndex,
            int pageCount,
            String... texts) {
        List<CandidatePage.Item> items = new ArrayList<>();
        for (int index = 0; index < texts.length; index++) {
            items.add(new CandidatePage.Item("c" + index, texts[index]));
        }
        return new CandidatePage(
                producer, generation, revision, pageIndex, pageCount, items);
    }

    private static final class Harness implements KeyboardCandidateBar.Listener {
        final Context context = ApplicationProvider.getApplicationContext();
        final List<CandidatePage.Selection> selections = new ArrayList<>();
        final List<CandidatePage.PageRequest> pageRequests = new ArrayList<>();
        final KeyboardCandidateBar bar = new KeyboardCandidateBar(context, this);

        Harness() {
            bar.setPlaintextVisible(true);
        }

        @Override
        public void onCandidateSelected(CandidatePage.Selection selection) {
            selections.add(selection);
        }

        @Override
        public void onPageRequested(CandidatePage.PageRequest request) {
            pageRequests.add(request);
        }
    }
}

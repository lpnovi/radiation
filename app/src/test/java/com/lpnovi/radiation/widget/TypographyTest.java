package com.lpnovi.radiation.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TypographyTest {

    /** Text block for a typical One UI row: ~96dp, minus visualizer lift and breathing room. */
    private static final float ROW = 96 - 5 - 10;

    @Test
    public void defaultsFitUnchanged() {
        Typography t = Typography.fitHeight(true, 15, 1, true, 13, ROW, 1f);
        assertEquals(15, t.titleSp, 0.01);
        assertEquals(13, t.artistSp, 0.01);
        assertTrue(t.showArtist);
    }

    @Test
    public void twoLineTitleWithArtistFitsATypicalRow() {
        Typography t = Typography.fitHeight(true, 15, 2, true, 13, ROW, 1f);
        assertEquals(2, t.titleLines);
        assertTrue(t.showArtist);
        assertTrue(t.needed(1f) <= ROW);
    }

    /** Never overlap: on a short row or with large system fonts, sizes rebalance, then lines drop. */
    @Test
    public void neverExceedsTheRowAcrossHeightsAndFontScales() {
        for (float row = 30; row <= 110; row += 5) {
            for (float scale : new float[]{0.85f, 1f, 1.3f, 1.6f}) {
                for (int lines = 1; lines <= 2; lines++) {
                    Typography t = Typography.fitHeight(true, 19, lines, true, 16, row, scale);
                    assertTrue("row " + row + " scale " + scale + " lines " + lines,
                            t.needed(scale) <= row || (t.titleSp == Typography.MIN_TITLE_SP && t.titleLines == 1
                                    && !t.showArtist));
                    assertTrue(t.titleSp >= Typography.MIN_TITLE_SP);
                }
            }
        }
    }

    @Test
    public void titleWinsOverArtistWhenSpaceRunsOut() {
        Typography t = Typography.fitHeight(true, 15, 2, true, 13, 34, 1.3f);
        assertTrue(t.showTitle);
        assertFalse(t.showArtist);
    }

    /** Fake measure: 0.55em per character, like a typical sans font. */
    private static Typography.Measure chars(int n) {
        return sp -> n * sp * 0.55f;
    }

    @Test
    public void shortTitleKeepsItsSize() {
        assertEquals(15, Typography.fitWidth(15, 1, 300, chars(12)), 0.01);
    }

    @Test
    public void longTitleShrinksALittleToFit() {
        // "Locked Away (feat. Adam Levine)": 31 chars; a little too wide at 15sp for 240px.
        float sp = Typography.fitWidth(15, 1, 240, chars(31));
        assertTrue(sp < 15 && sp >= 15 - Typography.MAX_SHRINK_SP);
        assertTrue(chars(31).width(sp) <= 240);
    }

    @Test
    public void veryLongTitleStopsShrinkingAndEllipsizes() {
        float sp = Typography.fitWidth(15, 1, 100, chars(60));
        assertEquals(15 - Typography.MAX_SHRINK_SP, sp, 0.01);
    }

    @Test
    public void neverBelowTheReadableFloor() {
        assertEquals(Typography.MIN_TITLE_SP, Typography.fitWidth(12, 1, 10, chars(60)), 0.01);
    }

    @Test
    public void twoLinesHoldMoreBeforeShrinking() {
        assertEquals(15, Typography.fitWidth(15, 2, 240, chars(31)), 0.01);
    }

    @Test
    public void artistStaysBelowAShrunkTitle() {
        assertEquals(11f, Typography.artistFor(12.5f, 13f), 0.01);   // title shrank: artist follows
        assertEquals(13f, Typography.artistFor(15f, 13f), 0.01);     // normal case untouched
        assertEquals(Typography.MIN_ARTIST_SP, Typography.artistFor(11f, 13f), 0.01); // floor
    }
}

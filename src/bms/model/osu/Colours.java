package bms.model.osu;

import org.w3c.dom.css.RGBColor;

import java.util.Vector;

public class Colours {
    public static class RGB {
        public Integer red = 0;
        public Integer green = 0;
        public Integer blue = 0;
    }
    public Vector<RGB> combo = new Vector<RGB>();
    public RGB sliderTrackOverride;
    public RGB sliderBorder;
}

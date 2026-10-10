// Geometry of the Cowork / Code light group.
//
// These lock down the two defects that shipped broken once already:
//   * the measured width must equal the width actually drawn, or the host
//     clips the tail of the group (the "Code" label lost its final letter);
//   * the group must scale with the slot height it is given, because
//     TrafficMonitor hands out anything from a half row to a full one.
#include "AgentLightItems.h"

#include <Windows.h>

#include <iostream>
#include <string>

namespace {

int failures = 0;

void Expect(bool condition, const std::string& message) {
    if (!condition) {
        std::cerr << "FAIL: " << message << '\n';
        ++failures;
    }
}

// A memory DC is enough: the renderer needs a device to measure text against,
// and drawing into a bitmap keeps the test headless.
class Surface {
public:
    Surface(int w, int h) : w_(w), h_(h) {
        HDC screen = GetDC(nullptr);
        dc_ = CreateCompatibleDC(screen);
        bmp_ = CreateCompatibleBitmap(screen, w, h);
        old_ = SelectObject(dc_, bmp_);
        ReleaseDC(nullptr, screen);
        Clear();
    }
    ~Surface() {
        SelectObject(dc_, old_);
        DeleteObject(bmp_);
        DeleteDC(dc_);
    }
    Surface(const Surface&) = delete;
    Surface& operator=(const Surface&) = delete;

    void Clear() {
        RECT r{0, 0, w_, h_};
        HBRUSH b = CreateSolidBrush(kBg);
        FillRect(dc_, &r, b);
        DeleteObject(b);
    }

    HDC dc() const { return dc_; }

    // Rightmost column carrying anything other than the background, or -1.
    int InkRight() const {
        for (int x = w_ - 1; x >= 0; --x) {
            for (int y = 0; y < h_; ++y) {
                if (GetPixel(dc_, x, y) != kBg) return x;
            }
        }
        return -1;
    }

    int InkLeft() const {
        for (int x = 0; x < w_; ++x) {
            for (int y = 0; y < h_; ++y) {
                if (GetPixel(dc_, x, y) != kBg) return x;
            }
        }
        return -1;
    }

    bool HasColor(COLORREF c) const {
        for (int y = 0; y < h_; ++y) {
            for (int x = 0; x < w_; ++x) {
                if (GetPixel(dc_, x, y) == c) return true;
            }
        }
        return false;
    }

    // Horizontal span of one exact colour, e.g. the chip's fill.
    bool ColorSpan(COLORREF c, int* left, int* right) const {
        int l = w_, r = -1;
        for (int y = 0; y < h_; ++y) {
            for (int x = 0; x < w_; ++x) {
                if (GetPixel(dc_, x, y) != c) continue;
                if (x < l) l = x;
                if (x > r) r = x;
            }
        }
        if (r < 0) return false;
        *left = l;
        *right = r;
        return true;
    }


    static constexpr COLORREF kBg = RGB(255, 0, 255);

private:
    int w_, h_;
    HDC dc_ = nullptr;
    HBITMAP bmp_ = nullptr;
    HGDIOBJ old_ = nullptr;
};

agentstatus::GroupStatus Group(agentstatus::Light light, int count, int total) {
    agentstatus::GroupStatus g;
    g.light = light;
    g.count = count;
    g.total = total;
    for (int i = 0; i < total; ++i) {
        agentstatus::SessionInfo s;
        s.state = light;
        s.key = L"k" + std::to_wstring(i);
        g.sessions.push_back(s);
    }
    return g;
}

} // namespace

int main() {
    using namespace agentstatus;

    const GroupStatus cowork = Group(Light::Green, 1, 1);
    const GroupStatus code = Group(Light::Yellow, 2, 2);
    const GroupStatus none;  // grey, no sessions

    LightOptions opts;  // defaults: both shown, chip on, inside dashboard

    // ---- Measure and draw must agree ----
    // This is the regression for the clipped "Code" label: the dashboard
    // reserves MeasureLightGroup() and then draws DrawLightGroup(); any
    // disagreement is invisible on screen except as a chopped-off tail.
    for (int slotH : {16, 20, 24, 32}) {
        Surface s(600, 48);
        const float scale = 1.0f;
        const float measured = MeasureLightGroup(
            s.dc(), scale, static_cast<float>(slotH), cowork, code, opts);
        Expect(measured > 0.0f, "A visible group must measure wider than zero");

        RECT bounds{0, 0, 600, 48};
        DashboardRenderer canvas(s.dc(), bounds, 96);
        const float drawn = DrawLightGroup(
            canvas, s.dc(), 10.0f, slotH / 2.0f, scale,
            static_cast<float>(slotH), cowork, code, opts, false);
        Expect(drawn == measured,
            "DrawLightGroup must consume exactly what MeasureLightGroup reported");
    }

    // The ink must also physically fit inside the measured box.
    for (int slotH : {16, 32}) {
        Surface s(600, 48);
        const float measured = MeasureLightGroup(
            s.dc(), 1.0f, static_cast<float>(slotH), cowork, code, opts);
        {
            RECT bounds{0, 0, 600, 48};
            DashboardRenderer canvas(s.dc(), bounds, 96);
            DrawLightGroup(canvas, s.dc(), 10.0f, slotH / 2.0f, 1.0f,
                           static_cast<float>(slotH), cowork, code, opts, false);
        }
        const int right = s.InkRight();
        Expect(right >= 0, "The group must actually draw something");
        Expect(right <= 10 + static_cast<int>(measured),
            "Drawn ink must stay within the measured width");
        Expect(s.InkLeft() >= 10,
            "The group must not draw left of its origin");
    }

    // ---- Scaling with the slot height ----
    // TrafficMonitor gives a plugin item half the window height when it shares
    // the row pair, so the group has to shrink instead of overflowing.
    {
        Surface s(600, 48);
        const float tall = MeasureLightGroup(s.dc(), 1.0f, 32.0f, cowork, code, opts);
        const float short_ = MeasureLightGroup(s.dc(), 1.0f, 16.0f, cowork, code, opts);
        Expect(short_ < tall, "A shorter slot must produce a narrower group");
        Expect(short_ > 0.0f, "A shorter slot must still produce a group");
    }

    // ---- Chip container ----
    {
        Surface s(600, 48);
        LightOptions withChip = opts;
        LightOptions noChip = opts;
        noChip.chip = false;
        const float a = MeasureLightGroup(s.dc(), 1.0f, 32.0f, cowork, code, withChip);
        const float b = MeasureLightGroup(s.dc(), 1.0f, 32.0f, cowork, code, noChip);
        Expect(a > b, "The chip must add its horizontal padding to the width");
    }
    {
        // The chip must really paint: it went missing once behind a wrong
        // assumption, and a grey-on-grey container is easy to lose.
        Surface s(600, 48);
        LightOptions o = opts;
        o.chip = true;
        {
            RECT bounds{0, 0, 600, 48};
            DashboardRenderer canvas(s.dc(), bounds, 96);
            DrawLightGroup(canvas, s.dc(), 10.0f, 16.0f, 1.0f, 32.0f,
                           cowork, code, o, false);
        }
        Expect(s.HasColor(RGB(229, 229, 234)),
            "The light-mode chip must fill with its container colour");

        // The container must actually WRAP the dots and labels rather than sit
        // beside or behind part of them. Measured against the solid dot fills,
        // which have exact interior pixels; glyph and corner antialiasing make
        // any tighter pixel assertion here unreliable.
        int chipL = 0, chipR = 0;
        int coworkDotL = 0, coworkDotR = 0, codeDotL = 0, codeDotR = 0;
        const bool haveChip = s.ColorSpan(RGB(229, 229, 234), &chipL, &chipR);
        const bool haveGreen =
            s.ColorSpan(RGB(100, 202, 120), &coworkDotL, &coworkDotR);
        const bool haveOrange =
            s.ColorSpan(RGB(238, 134, 62), &codeDotL, &codeDotR);
        Expect(haveChip && haveGreen && haveOrange,
            "Chip plus both status dots must all be painted");
        if (haveChip && haveGreen && haveOrange) {
            Expect(coworkDotL > chipL,
                "The chip must start left of the first dot");
            Expect(codeDotR < chipR,
                "The chip must extend past the last dot");
            Expect(coworkDotR < codeDotL,
                "Cowork must be drawn before Code inside the chip");
        }
    }
    {
        Surface s(600, 48);
        LightOptions o = opts;
        o.chip = true;
        {
            RECT bounds{0, 0, 600, 48};
            DashboardRenderer canvas(s.dc(), bounds, 96);
            DrawLightGroup(canvas, s.dc(), 10.0f, 16.0f, 1.0f, 32.0f,
                           cowork, code, o, true);
        }
        Expect(s.HasColor(RGB(58, 60, 65)),
            "The dark-mode chip must fill with its own container colour");
    }

    // ---- Hidden states collapse to nothing ----
    {
        Surface s(600, 48);
        LightOptions off = opts;
        off.showCowork = false;
        off.showCode = false;
        Expect(MeasureLightGroup(s.dc(), 1.0f, 32.0f, cowork, code, off) == 0.0f,
            "Both lights switched off must measure zero");

        RECT bounds{0, 0, 600, 48};
        DashboardRenderer canvas(s.dc(), bounds, 96);
        Expect(DrawLightGroup(canvas, s.dc(), 10.0f, 16.0f, 1.0f, 32.0f,
                              cowork, code, off, false) == 0.0f,
            "Both lights switched off must draw nothing");
        Expect(s.InkRight() == -1, "Nothing may be painted when both are off");
    }
    {
        // Code auto-hides while idle; Cowork never does, so the group survives.
        Surface s(600, 48);
        Expect(InlineLightHidden(true, none, opts),
            "An empty Code group must hide while auto-hide is on");
        Expect(!InlineLightHidden(false, none, opts),
            "An empty Cowork group must stay visible as a grey dot");
        const float only = MeasureLightGroup(s.dc(), 1.0f, 32.0f, none, none, opts);
        const float both = MeasureLightGroup(s.dc(), 1.0f, 32.0f, cowork, code, opts);
        Expect(only > 0.0f, "Cowork alone must still measure a group");
        Expect(only < both, "One light must be narrower than two");
    }
    {
        LightOptions keep = opts;
        keep.hideCodeWhenIdle = false;
        Expect(!InlineLightHidden(true, none, keep),
            "With auto-hide off, an idle Code light must stay visible");
    }

    // ---- Session count only shows for more than one ----
    {
        Surface s(600, 48);
        const float one = MeasureLightGroup(
            s.dc(), 1.0f, 32.0f, Group(Light::Green, 1, 1), none, opts);
        const float many = MeasureLightGroup(
            s.dc(), 1.0f, 32.0f, Group(Light::Green, 3, 3), none, opts);
        Expect(many > one, "A count badge must widen the light that carries it");

        LightOptions noCount = opts;
        noCount.showCount = false;
        Expect(MeasureLightGroup(s.dc(), 1.0f, 32.0f,
                                 Group(Light::Green, 3, 3), none, noCount) == one,
            "Switching the count off must match the single-session width");
    }

    // ---- A null device must never crash or invent a width ----
    {
        Expect(MeasureLightGroup(nullptr, 1.0f, 32.0f, cowork, code, opts) == 0.0f,
            "A null HDC must measure zero rather than guess");
    }

    if (failures == 0) {
        std::cout << "All AgentLight tests passed\n";
    }
    return failures == 0 ? 0 : 1;
}

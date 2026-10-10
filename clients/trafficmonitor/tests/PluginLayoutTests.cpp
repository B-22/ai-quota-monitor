// Host-level regression tests: load the built plugin DLL exactly the way
// TrafficMonitor does and check the two properties that broke the taskbar.
//
// 1. THE DISPLAY ITEM COUNT. TrafficMonitor's taskbar window is
//    TASKBAR_WND_HEIGHT tall and, in its default two-row layout, gives each
//    display item HALF of that unless the item has the row pair to itself.
//    Plugin API 7 has no way to ask for the full height, so the count is the
//    only lever. Registering the two indicator lights as extra items dropped
//    the dashboard to a half row: rings at half size, ring tags colliding with
//    the rings, every label sitting on top of its own value. The lights are
//    drawn INSIDE the dashboard, so the plugin must expose exactly one item.
//
// 2. REPORTED WIDTH vs PAINTED WIDTH. The host lays out using
//    GetItemWidthEx() and clips DrawItem() to it. The dashboard's estimate ran
//    short of its own layout, which was harmless while only padding sat at the
//    end and became a chopped-off label once the lights moved in.
//
// The DLL path arrives as argv[1] (CMake passes $<TARGET_FILE:AIUsage>).
#include "PluginInterface.h"

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

typedef ITMPlugin* (*GetInstanceFn)();

constexpr COLORREF kBg = RGB(255, 0, 255);

// Rightmost non-background column, or -1 when nothing was painted.
int InkRight(HDC dc, int w, int h) {
    for (int x = w - 1; x >= 0; --x) {
        for (int y = 0; y < h; ++y) {
            if (GetPixel(dc, x, y) != kBg) return x;
        }
    }
    return -1;
}

} // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::cerr << "usage: PluginLayoutTests <path to AIUsagePreview.dll>\n";
        return 2;
    }
    const std::string dllPath = argv[1];

    HMODULE mod = LoadLibraryA(dllPath.c_str());
    if (!mod) {
        std::cerr << "FAIL: LoadLibrary(" << dllPath << ") -> "
                  << GetLastError() << '\n';
        return 1;
    }
    auto entry = (GetInstanceFn)GetProcAddress(mod, "TMPluginGetInstance");
    if (!entry) {
        std::cerr << "FAIL: TMPluginGetInstance not exported\n";
        return 1;
    }
    ITMPlugin* plugin = entry();
    Expect(plugin != nullptr, "TMPluginGetInstance must return an instance");
    if (!plugin) return 1;

    // ---- 1. Exactly one display item in the default placement ----
    //
    // No config directory is supplied, so the plugin runs on its defaults —
    // which is also how it behaves on a first install.
    {
        Expect(plugin->GetItem(0) != nullptr,
            "Item 0 (the usage dashboard) must always be registered");
        Expect(plugin->GetItem(1) == nullptr,
            "With the lights drawn inside the dashboard, no second display item "
            "may be registered: extra items halve the dashboard's slot height");
        Expect(plugin->GetItem(2) == nullptr,
            "No third display item may be registered either");

        int count = 0;
        while (plugin->GetItem(count) != nullptr && count < 16) ++count;
        Expect(count == 1,
            "The host must enumerate exactly 1 display item, saw "
                + std::to_string(count));
    }

    // ---- 2. What the item paints must fit the width it asked for ----
    {
        IPluginItem* item = plugin->GetItem(0);
        Expect(item->IsCustomDraw(),
            "The dashboard must declare itself custom-draw");

        const int surfW = 1400, surfH = 64;
        HDC screen = GetDC(nullptr);
        HDC dc = CreateCompatibleDC(screen);
        HBITMAP bmp = CreateCompatibleBitmap(screen, surfW, surfH);
        HGDIOBJ old = SelectObject(dc, bmp);
        ReleaseDC(nullptr, screen);

        // Every height TrafficMonitor can hand over: a full window, the
        // two-thirds window of single-row mode, and a shared half row.
        for (int slotH : {32, 21, 16}) {
            const int x = 8;
            const int y = (surfH - slotH) / 2;

            // The item reports an estimate before its first paint and corrects
            // it from the real extent afterwards, exactly as the host's next
            // layout pass would pick up. Settle it first.
            for (int pass = 0; pass < 2; ++pass) {
                RECT all{0, 0, surfW, surfH};
                HBRUSH b = CreateSolidBrush(kBg);
                FillRect(dc, &all, b);
                DeleteObject(b);
                const int w = item->GetItemWidthEx(dc);
                Expect(w > 0, "The dashboard must report a positive width");
                // Deliberately generous: the item clips itself to the rect it
                // is given, which would hide an under-report behind its own
                // clip instead of failing the check below.
                item->DrawItem(dc, x, y, w + 300, slotH, false);
            }

            const int reported = item->GetItemWidthEx(dc);
            const int right = InkRight(dc, surfW, surfH);
            Expect(right >= 0,
                "The dashboard must paint something at slot height "
                    + std::to_string(slotH));
            Expect(right < x + reported,
                "Painted ink must fit the reported width at slot height "
                    + std::to_string(slotH) + " (ink ends at "
                    + std::to_string(right) + ", box ends at "
                    + std::to_string(x + reported) + ")");
        }

        // A taller slot must yield a taller drawing: this is what "the rings
        // shrank" looked like as a number.
        int inkHeights[2] = {0, 0};
        int idx = 0;
        for (int slotH : {32, 16}) {
            RECT all{0, 0, surfW, surfH};
            HBRUSH b = CreateSolidBrush(kBg);
            FillRect(dc, &all, b);
            DeleteObject(b);
            const int y = (surfH - slotH) / 2;
            item->DrawItem(dc, 8, y, item->GetItemWidthEx(dc) + 300, slotH, false);
            int top = surfH, bottom = -1;
            for (int yy = 0; yy < surfH; ++yy) {
                for (int xx = 0; xx < surfW; ++xx) {
                    if (GetPixel(dc, xx, yy) == kBg) continue;
                    if (yy < top) top = yy;
                    if (yy > bottom) bottom = yy;
                }
            }
            inkHeights[idx++] = bottom >= 0 ? bottom - top + 1 : 0;
        }
        Expect(inkHeights[0] > inkHeights[1],
            "A 32 px slot must draw taller than a 16 px slot ("
                + std::to_string(inkHeights[0]) + " vs "
                + std::to_string(inkHeights[1]) + ")");
        Expect(inkHeights[0] <= 32,
            "The drawing must stay inside a 32 px slot, got "
                + std::to_string(inkHeights[0]));
        Expect(inkHeights[1] <= 16,
            "The drawing must stay inside a 16 px slot, got "
                + std::to_string(inkHeights[1]));

        SelectObject(dc, old);
        DeleteObject(bmp);
        DeleteDC(dc);
    }

    // ---- Tooltip length ----
    //
    // The host pops a "遇到不适当的参数" message box, once per run, when
    // the tooltip runs past roughly 810 characters. It was found by
    // ramping the cap on a live taskbar: 736 and 809 are accepted, 855 is
    // not. The tip grows with every provider added and with error lines,
    // so the ceiling has to be enforced rather than assumed. This is the
    // no-data tip, which is the floor -- the plugin caps the populated
    // one at TooltipMaxChars.
    {
        const wchar_t* tip = plugin->GetTooltipInfo();
        Expect(tip != nullptr, "GetTooltipInfo must not return null");
        if (tip != nullptr) {
            const size_t len = wcslen(tip);
            Expect(len <= 800,
                "The tooltip must stay under the host limit, got "
                    + std::to_string(len));
        }
    }

    // ---- Plugin metadata the host relies on ----
    {
        Expect(plugin->GetAPIVersion() >= 7,
            "The plugin must declare at least plugin API version 7");
        const wchar_t* id = plugin->GetItem(0)->GetItemId();
        Expect(id != nullptr && *id != L'\0',
            "The dashboard item must expose a stable id for config.ini");
    }

    if (failures == 0) {
        std::cout << "All PluginLayout tests passed\n";
    }
    return failures == 0 ? 0 : 1;
}

#include "AgentLightItems.h"

#include <algorithm>
#include <cmath>

namespace agentstatus {
namespace {

constexpr const wchar_t* kFontFamily = L"Segoe UI";
// Matches the dashboard item's assumption about the taskbar slot the host
// paints into, used until the first real DrawItem rectangle arrives.
constexpr float kHostSlotHeight = 32.0f;

// Everything vertical is derived from the slot the light is drawn into.
// TrafficMonitor arranges display items in two rows unless `horizontal_arrange`
// is set, and the two layouts hand out very different heights; sizing from DPI
// alone would push the dot and the label past the slot edges in the short one.
struct LightMetrics {
    float dotRadius = 3.5f;
    float labelFont = 9.5f;
    float countFont = 9.0f;
    float gapAfterDot = 4.0f;
    float gapBeforeCount = 3.0f;
    float trailing = 4.0f;
};

LightMetrics Metrics(float scale, float slotHeight) {
    // Clamp the reference height so an unusually tall slot does not inflate
    // the light out of proportion with the rest of the taskbar.
    const float unit =
        std::clamp(slotHeight, 12.0f * scale, kHostSlotHeight * scale);
    LightMetrics m;
    m.dotRadius = std::max(2.0f, std::round(unit * 0.11f));
    m.labelFont = std::clamp(unit * 0.30f, 6.5f * scale, 9.5f * scale);
    m.countFont = std::clamp(unit * 0.28f, 6.0f * scale, 9.0f * scale);
    m.gapAfterDot = std::max(2.0f, std::round(unit * 0.13f));
    m.gapBeforeCount = std::max(2.0f, std::round(unit * 0.09f));
    m.trailing = std::max(2.0f, std::round(unit * 0.13f));
    return m;
}

COLORREF LabelColor(bool dark) {
    return dark ? RGB(203, 204, 208) : RGB(66, 68, 72);
}

const wchar_t* LabelFor(bool isCode) { return isCode ? L"Code" : L"Cowork"; }

COLORREF DotColorFor(Light light, bool dark) {
    switch (light) {
        case Light::Red:
            return dark ? RGB(220, 60, 60) : RGB(223, 61, 69);
        case Light::Yellow:
            return dark ? RGB(224, 170, 45) : RGB(238, 134, 62);
        case Light::Green:
            return dark ? RGB(46, 168, 120) : RGB(100, 202, 120);
        case Light::Gray:
        default:
            return dark ? RGB(84, 88, 95) : RGB(193, 196, 200);
    }
}

// A single session needs no number; the dot already says everything.
std::wstring CountFor(const GroupStatus& status, const LightOptions& opts) {
    if (!opts.showCount || status.total <= 1) return {};
    return std::to_wstring(status.count);
}

}  // namespace

namespace {

struct ChipMetrics {
    float padH = 6.0f;
    float padV = 3.0f;
    float radius = 4.5f;
    float height = 18.0f;
    float innerGap = 6.0f;
};

// Proportions lifted from the reference design (a 44 px bar with 8 px side
// padding, 6 px corners and an 18 px tall container matching the group
// dividers), expressed as fractions of the slot so they hold at any height.
ChipMetrics Chip(float scale, float slotHeight) {
    const float unit =
        std::clamp(slotHeight, 12.0f * scale, kHostSlotHeight * scale);
    ChipMetrics c;
    c.padH = std::max(3.0f, std::round(unit * 0.18f));
    c.padV = std::max(1.0f, std::round(unit * 0.09f));
    c.radius = std::max(2.0f, std::round(unit * 0.14f));
    c.height = std::max(10.0f, std::round(unit * 0.56f));
    c.innerGap = std::max(3.0f, std::round(unit * 0.18f));
    return c;
}

COLORREF ChipColor(bool dark) {
    // Light: the reference grey. Dark: a touch lighter than a dark taskbar,
    // so the container reads without glowing.
    return dark ? RGB(58, 60, 65) : RGB(229, 229, 234);
}

}  // namespace

bool InlineLightHidden(bool isCode, const GroupStatus& status,
                       const LightOptions& opts) {
    if (isCode) {
        if (!opts.showCode) return true;
        // Auto-hide applies to Code only: once every session has aged past the
        // idle timeout the watcher reports an empty group and the light gives
        // its space back.
        return opts.hideCodeWhenIdle && status.Empty();
    }
    return !opts.showCowork;
}

float MeasureInlineLight(HDC hdc, float scale, float slotHeight, bool isCode,
                         const GroupStatus& status, const LightOptions& opts,
                         bool trailing) {
    if (!hdc || InlineLightHidden(isCode, status, opts)) return 0.0f;
    const LightMetrics m = Metrics(scale, slotHeight);

    float w = m.dotRadius * 2.0f;
    if (opts.showLabel) {
        w += m.gapAfterDot;
        w += DashboardRenderer::MeasureTextWidth(
            hdc, LabelFor(isCode), kFontFamily, m.labelFont, false);
    }
    const std::wstring count = CountFor(status, opts);
    if (!count.empty()) {
        w += m.gapBeforeCount;
        w += DashboardRenderer::MeasureTextWidth(
            hdc, count.c_str(), kFontFamily, m.countFont, true);
    }
    // Trailing breathing room so the next element does not touch the text.
    return trailing ? w + m.trailing : w;
}

float DrawInlineLight(DashboardRenderer& canvas, HDC hdc, float x,
                      float centerY, float scale, float slotHeight,
                      bool isCode, const GroupStatus& status,
                      const LightOptions& opts, bool darkMode, bool trailing) {
    if (!hdc || InlineLightHidden(isCode, status, opts)) return 0.0f;
    const LightMetrics m = Metrics(scale, slotHeight);
    const COLORREF dot = DotColorFor(status.light, darkMode);

    float cursor = x;
    canvas.DrawDot(cursor + m.dotRadius, centerY, m.dotRadius, dot);
    cursor += m.dotRadius * 2.0f;

    if (opts.showLabel) {
        cursor += m.gapAfterDot;
        const float textW = DashboardRenderer::MeasureTextWidth(
            hdc, LabelFor(isCode), kFontFamily, m.labelFont, false);
        canvas.DrawTextCentered(
            LabelFor(isCode), cursor + textW / 2.0f, centerY,
            kFontFamily, m.labelFont, LabelColor(darkMode), false);
        cursor += textW;
    }

    const std::wstring count = CountFor(status, opts);
    if (!count.empty()) {
        cursor += m.gapBeforeCount;
        const float textW = DashboardRenderer::MeasureTextWidth(
            hdc, count.c_str(), kFontFamily, m.countFont, true);
        // The count is tinted with the light's own colour so a red "2" reads
        // as "two sessions are waiting on you" at a glance.
        canvas.DrawTextCentered(
            count.c_str(), cursor + textW / 2.0f, centerY,
            kFontFamily, m.countFont, dot, true);
        cursor += textW;
    }
    return (trailing ? cursor + m.trailing : cursor) - x;
}

float MeasureLightGroup(HDC hdc, float scale, float slotHeight,
                        const GroupStatus& cowork, const GroupStatus& code,
                        const LightOptions& opts) {
    if (!hdc) return 0.0f;
    const bool haveCowork = !InlineLightHidden(false, cowork, opts);
    const bool haveCode = !InlineLightHidden(true, code, opts);
    if (!haveCowork && !haveCode) return 0.0f;

    const ChipMetrics c = Chip(scale, slotHeight);
    float inner = 0.0f;
    if (haveCowork) {
        inner += MeasureInlineLight(
            hdc, scale, slotHeight, false, cowork, opts, false);
    }
    if (haveCowork && haveCode) inner += c.innerGap;
    if (haveCode) {
        inner += MeasureInlineLight(
            hdc, scale, slotHeight, true, code, opts, false);
    }
    return opts.chip ? inner + c.padH * 2.0f : inner;
}

float DrawLightGroup(DashboardRenderer& canvas, HDC hdc, float x,
                     float centerY, float scale, float slotHeight,
                     const GroupStatus& cowork, const GroupStatus& code,
                     const LightOptions& opts, bool darkMode) {
    const float total =
        MeasureLightGroup(hdc, scale, slotHeight, cowork, code, opts);
    if (total <= 0.0f) return 0.0f;

    const ChipMetrics c = Chip(scale, slotHeight);
    if (opts.chip) {
        canvas.FillRoundRect(
            x, centerY - c.height / 2.0f,
            x + total, centerY + c.height / 2.0f,
            c.radius, ChipColor(darkMode));
    }

    float cursor = x + (opts.chip ? c.padH : 0.0f);
    const bool haveCowork = !InlineLightHidden(false, cowork, opts);
    const bool haveCode = !InlineLightHidden(true, code, opts);
    if (haveCowork) {
        cursor += DrawInlineLight(canvas, hdc, cursor, centerY, scale,
                                  slotHeight, false, cowork, opts, darkMode,
                                  false);
        if (haveCode) cursor += c.innerGap;
    }
    if (haveCode) {
        DrawInlineLight(canvas, hdc, cursor, centerY, scale, slotHeight, true,
                        code, opts, darkMode, false);
    }
    return total;
}

// =====================================================================
// Standalone taskbar item
// =====================================================================

AgentLightItem::AgentLightItem(bool isCode, const wchar_t* id, const wchar_t* name)
    : isCode_(isCode), id_(id), name_(name) {}

void AgentLightItem::Update(const GroupStatus& status, const LightOptions& opts) {
    std::lock_guard<std::mutex> lk(mu_);
    status_ = status;
    opts_ = opts;
    // Width depends on the state (count digits, collapse-to-zero when the Code
    // light auto-hides), so every update invalidates the cached slot width.
    cachedW_ = 0;
}

bool AgentLightItem::Hidden() const {
    // While the lights ride inside the dashboard, the standalone items must
    // take no space at all, or the user sees each light twice.
    if (opts_.insideDashboard) return true;
    return InlineLightHidden(isCode_, status_, opts_);
}

const wchar_t* AgentLightItem::LabelText() const { return LabelFor(isCode_); }

COLORREF AgentLightItem::DotColor(Light light, bool dark) {
    return DotColorFor(light, dark);
}

std::wstring AgentLightItem::CountText() const {
    return CountFor(status_, opts_);
}

int AgentLightItem::GetItemWidth() const {
    std::lock_guard<std::mutex> lk(mu_);
    if (Hidden()) return 0;
    if (cachedW_ > 0) return cachedW_;
    // Pre-paint estimate; GetItemWidthEx replaces it once an HDC exists.
    int w = 14;
    if (opts_.showLabel) w += isCode_ ? 30 : 44;
    if (!CountText().empty()) w += 10;
    return w;
}

int AgentLightItem::GetItemWidthEx(void* hDC) const {
    if (!hDC) return GetItemWidth();
    HDC hdc = static_cast<HDC>(hDC);
    int dpi = GetDeviceCaps(hdc, LOGPIXELSX);
    if (dpi < 72) dpi = 96;
    const float scale = dpi / 96.0f;

    GroupStatus snap;
    LightOptions opts;
    bool hidden = false;
    float slotHeight = 0.0f;
    {
        std::lock_guard<std::mutex> lk(mu_);
        hidden = Hidden();
        snap = status_;
        opts = opts_;
        slotHeight = slotHeight_;
    }
    // Returning 0 removes the item from the taskbar layout entirely, which is
    // how the Code light disappears while you are not using Code.
    if (hidden) return 0;

    // The host queries the width before the first paint, so fall back to the
    // nominal slot height until DrawItem reports the real one.
    if (slotHeight <= 0.0f) slotHeight = kHostSlotHeight * scale;

    const int width = std::max(
        1, static_cast<int>(std::ceil(MeasureInlineLight(
               hdc, scale, slotHeight, isCode_, snap, opts))));
    {
        std::lock_guard<std::mutex> lk(mu_);
        cachedW_ = width;
    }
    return width;
}

void AgentLightItem::DrawItem(
    void* hDC, int x, int y, int w, int h, bool darkMode) {
    HDC hdc = static_cast<HDC>(hDC);
    if (!hdc) return;

    GroupStatus snap;
    LightOptions opts;
    bool hidden = false;
    {
        std::lock_guard<std::mutex> lk(mu_);
        hidden = Hidden();
        snap = status_;
        opts = opts_;
        // A slot height different from the last paint changes every metric,
        // so the cached width has to be dropped with it.
        if (h > 0 && slotHeight_ != static_cast<float>(h)) {
            slotHeight_ = static_cast<float>(h);
            cachedW_ = 0;
        }
    }
    if (hidden || w <= 0 || h <= 0) return;

    const int savedDc = SaveDC(hdc);
    int dpi = GetDeviceCaps(hdc, LOGPIXELSX);
    if (dpi < 72) dpi = 96;
    const float scale = dpi / 96.0f;

    const RECT bounds{x, y, x + std::max(1, w), y + std::max(1, h)};
    // The host may leave an unrelated clip selected; bound drawing to our own
    // rectangle so nothing bleeds into neighbouring items.
    SelectClipRgn(hdc, nullptr);
    IntersectClipRect(hdc, bounds.left, bounds.top, bounds.right, bounds.bottom);

    DashboardRenderer canvas(hdc, bounds, dpi);
    const float slotH = static_cast<float>(h);
    DrawInlineLight(canvas, hdc, static_cast<float>(x),
                    static_cast<float>(y) + slotH / 2.0f, scale, slotH,
                    isCode_, snap, opts, darkMode);

    RestoreDC(hdc, savedDc);
}

std::wstring AgentLightItem::TooltipLines() const {
    GroupStatus snap;
    bool off = false;
    {
        std::lock_guard<std::mutex> lk(mu_);
        // The tooltip follows the light being switched off, not the
        // inside-dashboard placement: the detail is worth showing either way.
        off = isCode_ ? !opts_.showCode : !opts_.showCowork;
        snap = status_;
    }
    if (off) return {};

    const wchar_t* stateText = L"";
    switch (snap.light) {
        case Light::Red:    stateText = L"等待你处理"; break;
        case Light::Yellow: stateText = L"工作中"; break;
        case Light::Green:  stateText = L"空闲"; break;
        default:            stateText = L"无会话"; break;
    }

    std::wstring out = LabelFor(isCode_);
    out += L"  ";
    out += stateText;
    if (snap.total > 0) {
        out += L" ";
        out += std::to_wstring(snap.count);
        out += L"/";
        out += std::to_wstring(snap.total);
    }

    // Sessions run along one line instead of one line each: the taskbar
    // tooltip is capped near 810 characters for the whole plugin, and four
    // sessions on four lines cost four leading indents and four newlines for
    // information that fits comfortably in the width already on screen.
    // The state mark is repeated only when it changes, so a run of sessions
    // in the same state reads as "[运行] a · b · c".
    constexpr size_t kMaxLineChars = 62;
    std::wstring lastMark;   // compared by value, not by literal address
    std::wstring line;
    int shown = 0;
    auto flush = [&]() {
        if (line.empty()) return;
        out += L"\n ";
        out += line;
        line.clear();
    };
    for (const auto& s : snap.sessions) {
        if (shown >= 4) {
            if (line.size() + 5 > kMaxLineChars) flush();
            line += line.empty() ? L"…" : L"  ·  …";
            break;
        }
        const wchar_t* mark = nullptr;
        switch (s.state) {
            case Light::Red:    mark = L"[等待]"; break;
            case Light::Yellow: mark = L"[运行]"; break;
            case Light::Green:  mark = L"[空闲]"; break;
            default: break;
        }
        std::wstring title = s.title;
        if (title.size() > 28) {
            title.resize(27);
            title += L"…";
        }
        std::wstring chunk;
        // A changed mark also breaks the run, so the label always applies to
        // the sessions that follow it on the same line.
        const bool markChanged = mark != nullptr && lastMark != mark;
        if (markChanged) {
            chunk += mark;
            chunk += L" ";
            lastMark = mark;
        }
        chunk += title;
        if (!line.empty() && line.size() + chunk.size() + 5 > kMaxLineChars) {
            flush();
            // A wrapped line restates the mark: the run it belonged to ended.
            if (mark != nullptr && !markChanged) {
                chunk = std::wstring(mark) + L" " + chunk;
            }
        }
        if (!line.empty()) line += L"  ·  ";
        line += chunk;
        ++shown;
    }
    flush();

    return out;
}

}  // namespace agentstatus

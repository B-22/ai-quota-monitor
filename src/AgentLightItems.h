#pragma once

// Two taskbar indicator lights, one per Claude Desktop surface.
//
// Each light shows the most urgent state among that surface's active sessions
// (red > yellow > green > grey) plus a count of how many sessions share it, so
// "one finished, one still running" reads as yellow rather than green.
//
// The Code light collapses to zero width after its idle timeout and reappears
// the moment a session starts. The Cowork light is permanent and shows a grey
// dot when nothing is running.

#include <Windows.h>

#include <mutex>
#include <string>

#include "AgentStatusWatcher.h"
#include "DashboardRenderer.h"
#include "PluginInterface.h"

namespace agentstatus {

struct LightOptions {
    bool showCowork = true;
    bool showCode = true;
    bool hideCodeWhenIdle = true;
    bool showLabel = true;   // draw "Cowork" / "Code" next to the dot
    bool showCount = true;   // draw the session count when more than one
    // Draw both lights inside the usage dashboard's own taskbar item instead
    // of registering them as two separate items.
    //
    // This is the default because of how TrafficMonitor sizes items. On its
    // standard two-row taskbar the dashboard receives the full ~32 px slot,
    // while a separate item is handed one short row — and the dashboard scales
    // its rings to whatever height it gets. Riding inside the dashboard keeps
    // the rings at full size AND puts the lights on the same baseline as
    // everything else, without forcing the taskbar into single-row mode.
    bool insideDashboard = true;
    // Draw a light-grey rounded container behind the pair. Without it the
    // dots and labels read as loose punctuation trailing the last info block;
    // the chip gives the group the same "container" weight the rings have.
    bool chip = true;
};

// The Cowork + Code pair drawn as ONE group: chip background, both lights and
// the gap between them. Measuring and drawing share the same layout, so the
// width reported can never disagree with what lands on screen.
float MeasureLightGroup(
    HDC hdc,
    float scale,
    float slotHeight,
    const GroupStatus& cowork,
    const GroupStatus& code,
    const LightOptions& opts);

// Draws at `x`, centred on `centerY`; returns the width consumed (0 when both
// lights are hidden, in which case nothing is drawn).
float DrawLightGroup(
    DashboardRenderer& canvas,
    HDC hdc,
    float x,
    float centerY,
    float scale,
    float slotHeight,
    const GroupStatus& cowork,
    const GroupStatus& code,
    const LightOptions& opts,
    bool darkMode);

// Width one inline light needs, and the drawing that fills it. Both mirror the
// standalone item exactly, so a light looks the same either way.
// `trailing` adds the gap that separates this light from whatever follows.
// The group layout turns it off for the last light so the chip's own right
// padding is not doubled.
float MeasureInlineLight(
    HDC hdc,
    float scale,
    float slotHeight,
    bool isCode,
    const GroupStatus& status,
    const LightOptions& opts,
    bool trailing = true);

// Draws at `x`, vertically centred on `centerY`; returns the width consumed.
float DrawInlineLight(
    DashboardRenderer& canvas,
    HDC hdc,
    float x,
    float centerY,
    float scale,
    float slotHeight,
    bool isCode,
    const GroupStatus& status,
    const LightOptions& opts,
    bool darkMode,
    bool trailing = true);

// True when this light contributes nothing (switched off, or the Code light
// auto-hidden while idle) and its width should collapse to zero.
bool InlineLightHidden(bool isCode, const GroupStatus& status,
                       const LightOptions& opts);

// One indicator. `IsCode` decides the label, the palette fallback and whether
// the item is allowed to collapse when idle.
class AgentLightItem final : public IPluginItem {
public:
    AgentLightItem(bool isCode, const wchar_t* id, const wchar_t* name);

    const wchar_t* GetItemName() const override { return name_; }
    const wchar_t* GetItemId() const override { return id_; }
    const wchar_t* GetItemLableText() const override { return L""; }
    const wchar_t* GetItemValueText() const override { return L""; }
    const wchar_t* GetItemValueSampleText() const override { return L""; }
    bool IsCustomDraw() const override { return true; }

    int GetItemWidth() const override;
    int GetItemWidthEx(void* hDC) const override;
    void DrawItem(void* hDC, int x, int y, int w, int h, bool darkMode) override;

    void Update(const GroupStatus& status, const LightOptions& opts);

    // Human-readable lines for the plugin tooltip.
    std::wstring TooltipLines() const;

private:
    bool Hidden() const;
    const wchar_t* LabelText() const;
    static COLORREF DotColor(Light light, bool darkMode);
    std::wstring CountText() const;

    const bool isCode_;
    const wchar_t* const id_;
    const wchar_t* const name_;

    mutable std::mutex mu_;
    GroupStatus status_;
    LightOptions opts_;
    mutable int cachedW_ = 0;
    mutable float slotHeight_ = 0.0f;
};

}  // namespace agentstatus

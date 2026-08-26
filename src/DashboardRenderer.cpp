#include "DashboardRenderer.h"

#include <d2d1.h>
#include <dwrite.h>
#include <gdiplus.h>
#include <wrl/client.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <map>
#include <mutex>
#include <string>

namespace {

using Microsoft::WRL::ComPtr;

struct FontKey {
    std::wstring family;
    int size64 = 0;
    bool medium = false;

    bool operator<(const FontKey& rhs) const {
        if (family != rhs.family) return family < rhs.family;
        if (size64 != rhs.size64) return size64 < rhs.size64;
        return medium < rhs.medium;
    }
};

struct SharedDirectWrite {
    std::once_flag initFlag;
    ComPtr<ID2D1Factory> d2dFactory;
    ComPtr<IDWriteFactory> writeFactory;
    std::mutex formatsMutex;
    std::map<FontKey, ComPtr<IDWriteTextFormat>> formats;
    bool ready = false;
};

SharedDirectWrite& Shared() {
    static SharedDirectWrite shared;
    return shared;
}

bool EnsureFactories() {
    SharedDirectWrite& shared = Shared();
    std::call_once(shared.initFlag, [&shared]() {
        D2D1_FACTORY_OPTIONS options{};
#if defined(_DEBUG)
        options.debugLevel = D2D1_DEBUG_LEVEL_INFORMATION;
#endif
        HRESULT hr = D2D1CreateFactory(
            D2D1_FACTORY_TYPE_MULTI_THREADED,
            __uuidof(ID2D1Factory),
            &options,
            reinterpret_cast<void**>(shared.d2dFactory.GetAddressOf()));
        if (FAILED(hr)) return;

        hr = DWriteCreateFactory(
            DWRITE_FACTORY_TYPE_SHARED,
            __uuidof(IDWriteFactory),
            reinterpret_cast<IUnknown**>(shared.writeFactory.GetAddressOf()));
        shared.ready = SUCCEEDED(hr);
    });
    return shared.ready;
}

ComPtr<IDWriteTextFormat> GetTextFormat(
    const wchar_t* fontFamily,
    float fontSizePx,
    bool mediumWeight) {
    if (!EnsureFactories()) return {};

    FontKey key{
        fontFamily ? fontFamily : L"Segoe UI",
        static_cast<int>(std::lround(fontSizePx * 64.0f)),
        mediumWeight};

    SharedDirectWrite& shared = Shared();
    std::lock_guard<std::mutex> lock(shared.formatsMutex);
    const auto found = shared.formats.find(key);
    if (found != shared.formats.end()) return found->second;

    ComPtr<IDWriteTextFormat> format;
    const HRESULT hr = shared.writeFactory->CreateTextFormat(
        key.family.c_str(),
        nullptr,
        mediumWeight ? DWRITE_FONT_WEIGHT_MEDIUM : DWRITE_FONT_WEIGHT_NORMAL,
        DWRITE_FONT_STYLE_NORMAL,
        DWRITE_FONT_STRETCH_NORMAL,
        std::max(1.0f, fontSizePx),
        L"zh-CN",
        &format);
    if (FAILED(hr)) return {};

    format->SetTextAlignment(DWRITE_TEXT_ALIGNMENT_LEADING);
    format->SetParagraphAlignment(DWRITE_PARAGRAPH_ALIGNMENT_NEAR);
    format->SetWordWrapping(DWRITE_WORD_WRAPPING_NO_WRAP);
    shared.formats.emplace(std::move(key), format);
    return format;
}

ComPtr<IDWriteTextLayout> CreateTextLayout(
    const wchar_t* text,
    const wchar_t* fontFamily,
    float fontSizePx,
    bool mediumWeight) {
    if (!text || !*text || !EnsureFactories()) return {};
    const ComPtr<IDWriteTextFormat> format =
        GetTextFormat(fontFamily, fontSizePx, mediumWeight);
    if (!format) return {};

    ComPtr<IDWriteTextLayout> layout;
    const HRESULT hr = Shared().writeFactory->CreateTextLayout(
        text,
        static_cast<UINT32>(wcslen(text)),
        format.Get(),
        1024.0f,
        std::max(32.0f, fontSizePx * 3.0f),
        &layout);
    return SUCCEEDED(hr) ? layout : ComPtr<IDWriteTextLayout>{};
}

D2D1_COLOR_F ToD2DColor(COLORREF color, float alpha = 1.0f) {
    return D2D1::ColorF(
        GetRValue(color) / 255.0f,
        GetGValue(color) / 255.0f,
        GetBValue(color) / 255.0f,
        alpha);
}

Gdiplus::Color ToGdiPlusColor(COLORREF color) {
    return Gdiplus::Color(
        255, GetRValue(color), GetGValue(color), GetBValue(color));
}

HFONT CreateFallbackFont(
    const wchar_t* fontFamily,
    float fontSizePx,
    bool mediumWeight) {
    return CreateFontW(
        -std::max(1, static_cast<int>(std::lround(fontSizePx))),
        0,
        0,
        0,
        mediumWeight ? FW_MEDIUM : FW_NORMAL,
        FALSE,
        FALSE,
        FALSE,
        DEFAULT_CHARSET,
        OUT_DEFAULT_PRECIS,
        CLIP_DEFAULT_PRECIS,
        CLEARTYPE_QUALITY,
        DEFAULT_PITCH | FF_SWISS,
        fontFamily ? fontFamily : L"Segoe UI");
}

float MeasureWithGdi(
    HDC hdc,
    const wchar_t* text,
    const wchar_t* fontFamily,
    float fontSizePx,
    bool mediumWeight) {
    if (!hdc || !text || !*text) return 0.0f;
    HFONT font = CreateFallbackFont(
        fontFamily, fontSizePx, mediumWeight);
    if (!font) return 0.0f;
    const HGDIOBJ previous = SelectObject(hdc, font);
    SIZE size{};
    GetTextExtentPoint32W(
        hdc, text, static_cast<int>(wcslen(text)), &size);
    SelectObject(hdc, previous);
    DeleteObject(font);
    return static_cast<float>(size.cx);
}

struct GdiVerticalMetrics {
    float baseline = 0.0f;
    float lineHeight = 0.0f;
};

GdiVerticalMetrics MeasureVerticalWithGdi(
    HDC hdc,
    const wchar_t* fontFamily,
    float fontSizePx,
    bool mediumWeight) {
    GdiVerticalMetrics result{
        fontSizePx * 0.8f, fontSizePx * 1.33f};
    if (!hdc) return result;
    HFONT font = CreateFallbackFont(fontFamily, fontSizePx, mediumWeight);
    if (!font) return result;
    const HGDIOBJ previous = SelectObject(hdc, font);
    TEXTMETRICW metrics{};
    if (GetTextMetricsW(hdc, &metrics)) {
        result.baseline = static_cast<float>(metrics.tmAscent);
        result.lineHeight =
            static_cast<float>(metrics.tmHeight + metrics.tmExternalLeading);
    }
    SelectObject(hdc, previous);
    DeleteObject(font);
    return result;
}

}  // namespace

struct DashboardRenderer::Impl {
    Impl(HDC targetHdc, const RECT& targetBounds, int targetDpi)
        : hdc(targetHdc),
          bounds(targetBounds),
          dpi(targetDpi > 0 ? targetDpi : 96) {
        if (!hdc || bounds.right <= bounds.left || bounds.bottom <= bounds.top) {
            return;
        }

        if (EnsureFactories()) {
            const D2D1_RENDER_TARGET_PROPERTIES properties =
                D2D1::RenderTargetProperties(
                    D2D1_RENDER_TARGET_TYPE_SOFTWARE,
                    D2D1::PixelFormat(
                        DXGI_FORMAT_B8G8R8A8_UNORM,
                        D2D1_ALPHA_MODE_IGNORE),
                    96.0f,
                    96.0f,
                    D2D1_RENDER_TARGET_USAGE_GDI_COMPATIBLE,
                    D2D1_FEATURE_LEVEL_DEFAULT);
            if (SUCCEEDED(Shared().d2dFactory->CreateDCRenderTarget(
                    &properties, &target))
                && SUCCEEDED(target->BindDC(hdc, &bounds))
                && SUCCEEDED(target->CreateSolidColorBrush(
                    D2D1::ColorF(D2D1::ColorF::Black), &solidBrush))) {
                D2D1_STROKE_STYLE_PROPERTIES strokeProperties =
                    D2D1::StrokeStyleProperties(
                        D2D1_CAP_STYLE_ROUND,
                        D2D1_CAP_STYLE_ROUND,
                        D2D1_CAP_STYLE_ROUND,
                        D2D1_LINE_JOIN_ROUND,
                        10.0f,
                        D2D1_DASH_STYLE_SOLID,
                        0.0f);
                Shared().d2dFactory->CreateStrokeStyle(
                    strokeProperties, nullptr, 0, &roundStroke);
                target->SetAntialiasMode(D2D1_ANTIALIAS_MODE_PER_PRIMITIVE);
                // TrafficMonitor's taskbar window is layered with a colour
                // key, so ClearType's subpixel filtering lands coloured
                // fringes on a background the plugin cannot see. Small labels
                // ended up as purple/green smears with no solid core.
                // Grayscale antialiasing keeps them neutral and readable.
                target->SetTextAntialiasMode(
                    D2D1_TEXT_ANTIALIAS_MODE_GRAYSCALE);
                target->BeginDraw();
                drawing = true;
                directWrite = true;
                return;
            }
            target.Reset();
            solidBrush.Reset();
            roundStroke.Reset();
        }

        gdi = std::make_unique<Gdiplus::Graphics>(hdc);
        gdi->SetSmoothingMode(Gdiplus::SmoothingModeHighQuality);
        gdi->SetPixelOffsetMode(Gdiplus::PixelOffsetModeHighQuality);
        gdi->SetTextRenderingHint(
            Gdiplus::TextRenderingHintAntiAliasGridFit);
    }

    ~Impl() {
        if (drawing && target) {
            target->EndDraw();
        }
    }

    D2D1_POINT_2F LocalPoint(float x, float y) const {
        return D2D1::Point2F(
            x - static_cast<float>(bounds.left),
            y - static_cast<float>(bounds.top));
    }

    D2D1_RECT_F LocalRect(
        float left,
        float top,
        float right,
        float bottom) const {
        return D2D1::RectF(
            left - static_cast<float>(bounds.left),
            top - static_cast<float>(bounds.top),
            right - static_cast<float>(bounds.left),
            bottom - static_cast<float>(bounds.top));
    }

    ComPtr<IDWriteTextLayout> Layout(
        const wchar_t* text,
        const wchar_t* family,
        float size,
        bool medium) const {
        return CreateTextLayout(text, family, size, medium);
    }

    void SetBrush(COLORREF color) {
        if (solidBrush) solidBrush->SetColor(ToD2DColor(color));
    }

    HDC hdc = nullptr;
    RECT bounds{};
    int dpi = 96;
    bool directWrite = false;
    bool drawing = false;
    ComPtr<ID2D1DCRenderTarget> target;
    ComPtr<ID2D1SolidColorBrush> solidBrush;
    ComPtr<ID2D1StrokeStyle> roundStroke;
    std::unique_ptr<Gdiplus::Graphics> gdi;
};

DashboardRenderer::DashboardRenderer(
    HDC hdc,
    const RECT& bounds,
    int dpi)
    : impl_(std::make_unique<Impl>(hdc, bounds, dpi)) {}

DashboardRenderer::~DashboardRenderer() = default;

bool DashboardRenderer::UsesDirectWrite() const {
    return impl_ && impl_->directWrite;
}

float DashboardRenderer::MeasureTextWidth(
    HDC hdc,
    const wchar_t* text,
    const wchar_t* fontFamily,
    float fontSizePx,
    bool mediumWeight) {
    const ComPtr<IDWriteTextLayout> layout =
        CreateTextLayout(text, fontFamily, fontSizePx, mediumWeight);
    if (layout) {
        DWRITE_TEXT_METRICS metrics{};
        if (SUCCEEDED(layout->GetMetrics(&metrics))) {
            return metrics.widthIncludingTrailingWhitespace;
        }
    }
    return MeasureWithGdi(
        hdc, text, fontFamily, fontSizePx, mediumWeight);
}

float DashboardRenderer::MeasureBaseline(
    HDC hdc,
    const wchar_t* fontFamily,
    float fontSizePx,
    bool mediumWeight) {
    const ComPtr<IDWriteTextLayout> layout =
        CreateTextLayout(L"Hg", fontFamily, fontSizePx, mediumWeight);
    if (layout) {
        DWRITE_LINE_METRICS line{};
        UINT32 count = 0;
        if (layout->GetLineMetrics(&line, 1, &count) != E_NOT_SUFFICIENT_BUFFER
            && count >= 1) {
            return line.baseline;
        }
    }
    return MeasureVerticalWithGdi(
        hdc, fontFamily, fontSizePx, mediumWeight).baseline;
}

float DashboardRenderer::MeasureLineHeight(
    HDC hdc,
    const wchar_t* fontFamily,
    float fontSizePx,
    bool mediumWeight) {
    const ComPtr<IDWriteTextLayout> layout =
        CreateTextLayout(L"Hg", fontFamily, fontSizePx, mediumWeight);
    if (layout) {
        DWRITE_LINE_METRICS line{};
        UINT32 count = 0;
        if (layout->GetLineMetrics(&line, 1, &count) != E_NOT_SUFFICIENT_BUFFER
            && count >= 1) {
            return line.height;
        }
    }
    return MeasureVerticalWithGdi(
        hdc, fontFamily, fontSizePx, mediumWeight).lineHeight;
}

float DashboardRenderer::TextWidth(
    const wchar_t* text,
    const wchar_t* fontFamily,
    float fontSizePx,
    bool mediumWeight) const {
    return MeasureTextWidth(
        impl_ ? impl_->hdc : nullptr,
        text,
        fontFamily,
        fontSizePx,
        mediumWeight);
}

void DashboardRenderer::DrawArc(
    float centerX,
    float centerY,
    float radius,
    float strokeWidth,
    int percent,
    COLORREF trackColor,
    COLORREF valueColor) {
    if (!impl_) return;
    if (impl_->directWrite) {
        const D2D1_ELLIPSE ellipse{
            impl_->LocalPoint(centerX, centerY), radius, radius};
        impl_->SetBrush(trackColor);
        impl_->target->DrawEllipse(
            ellipse,
            impl_->solidBrush.Get(),
            strokeWidth,
            impl_->roundStroke.Get());

        const int clamped = std::clamp(percent, 0, 100);
        if (clamped <= 0) return;
        impl_->SetBrush(valueColor);
        if (clamped >= 100) {
            impl_->target->DrawEllipse(
                ellipse,
                impl_->solidBrush.Get(),
                strokeWidth,
                impl_->roundStroke.Get());
            return;
        }

        const float startAngle = -90.0f;
        const float sweep = clamped * 3.6f;
        const float endAngle = startAngle + sweep;
        constexpr float kPi = 3.14159265358979323846f;
        const auto pointAt = [&](float degrees) {
            const float radians = degrees * kPi / 180.0f;
            return impl_->LocalPoint(
                centerX + std::cos(radians) * radius,
                centerY + std::sin(radians) * radius);
        };

        ComPtr<ID2D1PathGeometry> geometry;
        if (FAILED(Shared().d2dFactory->CreatePathGeometry(&geometry))) return;
        ComPtr<ID2D1GeometrySink> sink;
        if (FAILED(geometry->Open(&sink))) return;
        sink->BeginFigure(pointAt(startAngle), D2D1_FIGURE_BEGIN_HOLLOW);
        D2D1_ARC_SEGMENT segment{};
        segment.point = pointAt(endAngle);
        segment.size = D2D1::SizeF(radius, radius);
        segment.rotationAngle = 0.0f;
        segment.sweepDirection = D2D1_SWEEP_DIRECTION_CLOCKWISE;
        segment.arcSize = sweep > 180.0f
            ? D2D1_ARC_SIZE_LARGE
            : D2D1_ARC_SIZE_SMALL;
        sink->AddArc(segment);
        sink->EndFigure(D2D1_FIGURE_END_OPEN);
        if (SUCCEEDED(sink->Close())) {
            impl_->target->DrawGeometry(
                geometry.Get(),
                impl_->solidBrush.Get(),
                strokeWidth,
                impl_->roundStroke.Get());
        }
        return;
    }

    if (!impl_->gdi) return;
    const float left = centerX - radius;
    const float top = centerY - radius;
    const float diameter = radius * 2.0f;
    Gdiplus::Pen track(ToGdiPlusColor(trackColor), strokeWidth);
    track.SetStartCap(Gdiplus::LineCapRound);
    track.SetEndCap(Gdiplus::LineCapRound);
    impl_->gdi->DrawArc(&track, left, top, diameter, diameter, 0.0f, 360.0f);
    const int clamped = std::clamp(percent, 0, 100);
    if (clamped > 0) {
        Gdiplus::Pen value(ToGdiPlusColor(valueColor), strokeWidth);
        value.SetStartCap(Gdiplus::LineCapRound);
        value.SetEndCap(Gdiplus::LineCapRound);
        impl_->gdi->DrawArc(
            &value, left, top, diameter, diameter,
            -90.0f, clamped * 3.6f);
    }
}

void DashboardRenderer::DrawTextAt(
    const wchar_t* text,
    float x,
    float y,
    const wchar_t* fontFamily,
    float fontSizePx,
    COLORREF color,
    bool mediumWeight) {
    if (!impl_ || !text || !*text) return;
    if (impl_->directWrite) {
        const ComPtr<IDWriteTextLayout> layout =
            impl_->Layout(text, fontFamily, fontSizePx, mediumWeight);
        if (!layout) return;
        impl_->SetBrush(color);
        impl_->target->DrawTextLayout(
            impl_->LocalPoint(x, y),
            layout.Get(),
            impl_->solidBrush.Get(),
            D2D1_DRAW_TEXT_OPTIONS_NONE);
        return;
    }

    if (!impl_->hdc) return;
    HFONT font = CreateFallbackFont(
        fontFamily, fontSizePx, mediumWeight);
    if (!font) return;
    const HGDIOBJ previous = SelectObject(impl_->hdc, font);
    SetBkMode(impl_->hdc, TRANSPARENT);
    SetTextColor(impl_->hdc, color);
    RECT rect{
        static_cast<LONG>(std::floor(x)),
        static_cast<LONG>(std::floor(y)),
        static_cast<LONG>(std::ceil(x + 1024.0f)),
        static_cast<LONG>(std::ceil(y + fontSizePx * 3.0f))};
    DrawTextW(
        impl_->hdc, text, -1, &rect,
        DT_LEFT | DT_TOP | DT_NOCLIP | DT_SINGLELINE);
    SelectObject(impl_->hdc, previous);
    DeleteObject(font);
}

void DashboardRenderer::DrawGradientTextAt(
    const wchar_t* text,
    float x,
    float y,
    const wchar_t* fontFamily,
    float fontSizePx,
    bool mediumWeight,
    bool darkMode,
    COLORREF customStart,
    COLORREF customEnd) {
    if (!impl_ || !text || !*text) return;
    const COLORREF start = customStart != CLR_INVALID
        ? customStart
        : (darkMode ? RGB(255, 209, 102) : RGB(132, 20, 48));
    const COLORREF end = customEnd != CLR_INVALID
        ? customEnd
        : (darkMode ? RGB(255, 94, 168) : RGB(82, 28, 128));

    if (impl_->directWrite) {
        const ComPtr<IDWriteTextLayout> layout =
            impl_->Layout(text, fontFamily, fontSizePx, mediumWeight);
        if (!layout) return;
        DWRITE_TEXT_METRICS metrics{};
        layout->GetMetrics(&metrics);

        D2D1_GRADIENT_STOP stops[2]{
            {0.0f, ToD2DColor(start)},
            {1.0f, ToD2DColor(end)}};
        ComPtr<ID2D1GradientStopCollection> collection;
        if (FAILED(impl_->target->CreateGradientStopCollection(
                stops, 2, &collection))) {
            DrawTextAt(
                text, x, y, fontFamily, fontSizePx,
                start, mediumWeight);
            return;
        }
        ComPtr<ID2D1LinearGradientBrush> brush;
        const D2D1_LINEAR_GRADIENT_BRUSH_PROPERTIES properties{
            impl_->LocalPoint(x, y),
            impl_->LocalPoint(
                x + std::max(1.0f, metrics.width), y)};
        if (FAILED(impl_->target->CreateLinearGradientBrush(
                properties, collection.Get(), &brush))) {
            DrawTextAt(
                text, x, y, fontFamily, fontSizePx,
                start, mediumWeight);
            return;
        }
        impl_->target->DrawTextLayout(
            impl_->LocalPoint(x, y),
            layout.Get(),
            brush.Get(),
            D2D1_DRAW_TEXT_OPTIONS_NONE);
        return;
    }

    if (!impl_->gdi) return;
    Gdiplus::Font font(
        fontFamily,
        fontSizePx,
        mediumWeight
            ? Gdiplus::FontStyleBold
            : Gdiplus::FontStyleRegular,
        Gdiplus::UnitPixel);
    Gdiplus::RectF bounds(x, y, 180.0f, fontSizePx * 3.0f);
    Gdiplus::LinearGradientBrush brush(
        bounds,
        ToGdiPlusColor(start),
        ToGdiPlusColor(end),
        Gdiplus::LinearGradientModeHorizontal);
    Gdiplus::StringFormat format(
        Gdiplus::StringFormat::GenericTypographic());
    format.SetFormatFlags(
        format.GetFormatFlags() | Gdiplus::StringFormatFlagsNoWrap);
    impl_->gdi->DrawString(
        text, -1, &font, Gdiplus::PointF(x, y), &format, &brush);
}

void DashboardRenderer::DrawLightning(
    float left,
    float top,
    float width,
    float height,
    COLORREF startColor,
    COLORREF endColor) {
    if (!impl_ || width <= 0.0f || height <= 0.0f) return;

    // A compact SVG-like bolt: top tip, left shoulder, inner notch, bottom
    // tip, right shoulder, and the upper inner notch. The proportions are
    // intentionally broad so the mark remains legible at 32px taskbar height.
    constexpr std::array<D2D1_POINT_2F, 6> kBolt = {{
        {0.58f, 0.04f},
        {0.17f, 0.54f},
        {0.44f, 0.54f},
        {0.38f, 0.96f},
        {0.83f, 0.46f},
        {0.56f, 0.46f},
    }};
    const auto pointAt = [&](const D2D1_POINT_2F& point) {
        return impl_->LocalPoint(
            left + point.x * width,
            top + point.y * height);
    };

    if (impl_->directWrite) {
        ComPtr<ID2D1PathGeometry> geometry;
        if (FAILED(Shared().d2dFactory->CreatePathGeometry(&geometry))) return;
        ComPtr<ID2D1GeometrySink> sink;
        if (FAILED(geometry->Open(&sink))) return;
        sink->BeginFigure(pointAt(kBolt[0]), D2D1_FIGURE_BEGIN_FILLED);
        for (size_t i = 1; i < kBolt.size(); ++i) {
            sink->AddLine(pointAt(kBolt[i]));
        }
        sink->EndFigure(D2D1_FIGURE_END_CLOSED);
        if (FAILED(sink->Close())) return;

        D2D1_GRADIENT_STOP stops[2]{
            {0.0f, ToD2DColor(startColor)},
            {1.0f, ToD2DColor(endColor)}};
        ComPtr<ID2D1GradientStopCollection> collection;
        if (FAILED(impl_->target->CreateGradientStopCollection(
                stops, 2, &collection))) return;
        ComPtr<ID2D1LinearGradientBrush> brush;
        const D2D1_LINEAR_GRADIENT_BRUSH_PROPERTIES properties{
            impl_->LocalPoint(left, top),
            impl_->LocalPoint(left + width, top + height)};
        if (FAILED(impl_->target->CreateLinearGradientBrush(
                properties, collection.Get(), &brush))) return;
        impl_->target->FillGeometry(geometry.Get(), brush.Get());
        return;
    }

    if (!impl_->gdi) return;
    Gdiplus::PointF points[kBolt.size()];
    for (size_t i = 0; i < kBolt.size(); ++i) {
        points[i] = Gdiplus::PointF(
            left + kBolt[i].x * width,
            top + kBolt[i].y * height);
    }
    Gdiplus::LinearGradientBrush brush(
        Gdiplus::RectF(left, top, width, height),
        ToGdiPlusColor(startColor),
        ToGdiPlusColor(endColor),
        Gdiplus::LinearGradientModeForwardDiagonal);
    impl_->gdi->FillPolygon(&brush, points, static_cast<INT>(kBolt.size()));
}

void DashboardRenderer::DrawTextCentered(
    const wchar_t* text,
    float centerX,
    float centerY,
    const wchar_t* fontFamily,
    float fontSizePx,
    COLORREF color,
    bool mediumWeight) {
    if (!impl_ || !text || !*text) return;
    if (impl_->directWrite) {
        const ComPtr<IDWriteTextLayout> layout =
            impl_->Layout(text, fontFamily, fontSizePx, mediumWeight);
        if (!layout) return;
        DWRITE_TEXT_METRICS metrics{};
        if (FAILED(layout->GetMetrics(&metrics))) return;
        impl_->SetBrush(color);
        impl_->target->DrawTextLayout(
            impl_->LocalPoint(
                centerX - metrics.widthIncludingTrailingWhitespace / 2.0f,
                centerY - metrics.height / 2.0f),
            layout.Get(),
            impl_->solidBrush.Get(),
            D2D1_DRAW_TEXT_OPTIONS_NONE);
        return;
    }

    const float width = MeasureWithGdi(
        impl_->hdc, text, fontFamily, fontSizePx, mediumWeight);
    DrawTextAt(
        text,
        centerX - width / 2.0f,
        centerY - fontSizePx / 2.0f,
        fontFamily,
        fontSizePx,
        color,
        mediumWeight);
}

void DashboardRenderer::DrawLine(
    float x1,
    float y1,
    float x2,
    float y2,
    COLORREF color,
    float strokeWidth) {
    if (!impl_) return;
    if (impl_->directWrite) {
        impl_->SetBrush(color);
        impl_->target->DrawLine(
            impl_->LocalPoint(x1, y1),
            impl_->LocalPoint(x2, y2),
            impl_->solidBrush.Get(),
            strokeWidth,
            impl_->roundStroke.Get());
        return;
    }
    if (!impl_->gdi) return;
    Gdiplus::Pen pen(ToGdiPlusColor(color), strokeWidth);
    pen.SetStartCap(Gdiplus::LineCapRound);
    pen.SetEndCap(Gdiplus::LineCapRound);
    impl_->gdi->DrawLine(&pen, x1, y1, x2, y2);
}

void DashboardRenderer::DrawFreshnessUnderline(
    float centerX,
    float y,
    COLORREF color,
    int dpi) {
    const float scale = std::max(0.75f, dpi / 96.0f);
    const float halfWidth = std::max(3.0f, 4.0f * scale);
    const float stroke = std::max(1.0f, 1.5f * scale);
    DrawLine(
        centerX - halfWidth,
        y,
        centerX + halfWidth,
        y,
        color,
        stroke);
}

void DashboardRenderer::FillRoundRect(
    float left,
    float top,
    float right,
    float bottom,
    float radius,
    COLORREF color) {
    if (!impl_) return;
    if (right <= left || bottom <= top) return;
    // A radius past half the shorter side would invert the corner arcs.
    const float r = std::min(
        radius, std::min(right - left, bottom - top) / 2.0f);

    if (impl_->directWrite) {
        impl_->SetBrush(color);
        impl_->target->FillRoundedRectangle(
            D2D1::RoundedRect(
                impl_->LocalRect(left, top, right, bottom), r, r),
            impl_->solidBrush.Get());
        return;
    }
    if (!impl_->gdi) return;

    Gdiplus::SolidBrush brush(ToGdiPlusColor(color));
    if (r <= 0.5f) {
        impl_->gdi->FillRectangle(
            &brush, left, top, right - left, bottom - top);
        return;
    }
    const float d = r * 2.0f;
    Gdiplus::GraphicsPath path;
    path.AddArc(left, top, d, d, 180.0f, 90.0f);
    path.AddArc(right - d, top, d, d, 270.0f, 90.0f);
    path.AddArc(right - d, bottom - d, d, d, 0.0f, 90.0f);
    path.AddArc(left, bottom - d, d, d, 90.0f, 90.0f);
    path.CloseFigure();
    impl_->gdi->FillPath(&brush, &path);
}

void DashboardRenderer::DrawDot(
    float centerX,
    float centerY,
    float radius,
    COLORREF color) {
    if (!impl_) return;
    if (impl_->directWrite) {
        impl_->SetBrush(color);
        impl_->target->FillEllipse(
            D2D1::Ellipse(
                impl_->LocalPoint(centerX, centerY),
                radius,
                radius),
            impl_->solidBrush.Get());
        return;
    }
    if (!impl_->gdi) return;
    Gdiplus::SolidBrush brush(ToGdiPlusColor(color));
    impl_->gdi->FillEllipse(
        &brush,
        centerX - radius,
        centerY - radius,
        radius * 2.0f,
        radius * 2.0f);
}

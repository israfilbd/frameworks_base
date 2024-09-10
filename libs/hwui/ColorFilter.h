/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef COLORFILTER_H_
#define COLORFILTER_H_

#include <stdint.h>

#include <memory>

#include "GraphicsJNI.h"
#include "SkColorFilter.h"
#include "SkiaWrapper.h"

namespace android {
namespace uirenderer {

class ColorFilter : public SkiaWrapper<SkColorFilter> {
public:
    static ColorFilter* fromJava(jlong handle) { return reinterpret_cast<ColorFilter*>(handle); }

protected:
    ColorFilter() = default;
};

class BlendModeColorFilter : public ColorFilter {
public:
    BlendModeColorFilter(SkColor color, SkBlendMode mode) : mColor(color), mMode(mode) {}

private:
    sk_sp<SkColorFilter> createInstance() override { return SkColorFilters::Blend(mColor, mMode); }

private:
    const SkColor mColor;
    const SkBlendMode mMode;
};

class LightingFilter : public ColorFilter {
public:
    LightingFilter(SkColor mul, SkColor add) : mMul(mul), mAdd(add) {}

    void setMul(SkColor mul) {
        mMul = mul;
        discardInstance();
    }

    void setAdd(SkColor add) {
        mAdd = add;
        discardInstance();
    }

private:
    sk_sp<SkColorFilter> createInstance() override { return SkColorFilters::Lighting(mMul, mAdd); }

private:
    SkColor mMul;
    SkColor mAdd;
};

class ColorMatrixColorFilter : public ColorFilter {
public:
    ColorMatrixColorFilter(std::vector<float>&& matrix) : mMatrix(std::move(matrix)) {}

    void setMatrix(std::vector<float>&& matrix) {
        mMatrix = std::move(matrix);
        discardInstance();
    }

private:
    sk_sp<SkColorFilter> createInstance() override {
        return SkColorFilters::Matrix(mMatrix.data());
    }

private:
    std::vector<float> mMatrix;
};

}  // namespace uirenderer
}  // namespace android

#endif  // COLORFILTER_H_

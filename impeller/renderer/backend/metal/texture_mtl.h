// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#pragma once

#include <Metal/Metal.h>

#include "flutter/fml/macros.h"
#include "impeller/base/backend_cast.h"
#include "impeller/core/texture.h"

namespace impeller {

class TextureMTL final : public Texture,
                         public BackendCast<TextureMTL, Texture> {
 public:
  /// @brief This callback needs to always return the same texture when called
  ///        multiple times.
  using AcquireTextureProc = std::function<id<MTLTexture>()>;

  TextureMTL(TextureDescriptor desc,
             const AcquireTextureProc& aquire_proc,
             bool wrapped = false,
             bool drawable = false);

  static std::shared_ptr<TextureMTL> Wrapper(
      TextureDescriptor desc,
      id<MTLTexture> texture,
      std::function<void()> deletion_proc = nullptr);

  static std::shared_ptr<TextureMTL> Create(TextureDescriptor desc,
                                            id<MTLTexture> texture);

  // |Texture|
  ~TextureMTL() override;

  id<MTLTexture> GetMTLTexture() const;

  bool IsWrapped() const;

  /// @brief Whether or not this texture is wrapping a Metal drawable.
  bool IsDrawable() const;

  // |Texture|
  bool IsValid() const override;

  bool GenerateMipmap(id<MTLBlitCommandEncoder> encoder);

 private:
  AcquireTextureProc aquire_proc_ = {};
  bool is_valid_ = false;
  bool is_wrapped_ = false;
  bool is_drawable_ = false;

  // |Texture|
  void SetLabel(std::string_view label) override;

  // |Texture|
  bool OnSetContents(const uint8_t* contents,
                     size_t length,
                     size_t slice) override;

  // |Texture|
  bool OnSetContents(std::shared_ptr<const fml::Mapping> mapping,
                     size_t slice) override;
  // |Texture|
  ISize GetSize() const override;

  TextureMTL(const TextureMTL&) = delete;

  TextureMTL& operator=(const TextureMTL&) = delete;
};

}  // namespace impeller

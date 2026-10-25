#!/bin/bash
# Cross-compile for Windows from Linux using MinGW-w64
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$SCRIPT_DIR/.."
BUILD_DIR="$PROJECT_DIR/build-win64"
DEPS_DIR="$BUILD_DIR/_deps"
OUT_DIR="$BUILD_DIR/package"

echo "=== Setting up Windows cross-compilation ==="

mkdir -p "$DEPS_DIR" "$OUT_DIR"

# --- Download GLFW prebuilt Windows binaries ---
GLFW_VER="3.4"
GLFW_ZIP="glfw-${GLFW_VER}.bin.WIN64.zip"
GLFW_DIR="$DEPS_DIR/glfw-${GLFW_VER}.bin.WIN64"

if [ ! -d "$GLFW_DIR" ]; then
    echo "Downloading GLFW ${GLFW_VER} Windows binaries..."
    wget -q "https://github.com/glfw/glfw/releases/download/${GLFW_VER}/${GLFW_ZIP}" \
         -O "$DEPS_DIR/${GLFW_ZIP}"
    cd "$DEPS_DIR" && unzip -q "${GLFW_ZIP}" && cd "$PROJECT_DIR"
    echo "GLFW downloaded."
else
    echo "GLFW already downloaded."
fi

# --- Create Vulkan import library for MinGW ---
VULKAN_LIB="$DEPS_DIR/libvulkan-1.a"
if [ ! -f "$VULKAN_LIB" ]; then
    echo "Creating Vulkan import library..."
    x86_64-w64-mingw32-dlltool \
        -d "$PROJECT_DIR/cmake/vulkan-1.def" \
        -l "$VULKAN_LIB" \
        -k
    echo "Vulkan import library created."
fi

# --- Copy Vulkan headers (platform-independent) ---
VK_HEADERS_DIR="$DEPS_DIR/vulkan-headers/include"
if [ ! -d "$VK_HEADERS_DIR/vulkan" ]; then
    echo "Copying Vulkan headers..."
    mkdir -p "$VK_HEADERS_DIR"
    cp -r /usr/include/vulkan "$VK_HEADERS_DIR/"
    cp -r /usr/include/vk_video "$VK_HEADERS_DIR/" 2>/dev/null || true
fi

# --- Configure with CMake ---
echo "Configuring CMake..."
cd "$BUILD_DIR"
rm -rf CMakeCache.txt CMakeFiles
cmake "$PROJECT_DIR" \
    -DCMAKE_TOOLCHAIN_FILE="$PROJECT_DIR/cmake/mingw-w64.cmake" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_PREFIX_PATH="$GLFW_DIR" \
    -DGLFW_LIBRARY="$GLFW_DIR/lib-mingw-w64/libglfw3.a" \
    -DGLFW_INCLUDE_DIR="$GLFW_DIR/include" \
    -DVulkan_INCLUDE_DIR="$DEPS_DIR/vulkan-headers/include" \
    -DVulkan_LIBRARY="$VULKAN_LIB" \
    -DCMAKE_EXE_LINKER_FLAGS="-static -static-libgcc -static-libstdc++"

# --- Build ---
echo "Building..."
make -j$(nproc)

# --- Package ---
echo "Packaging..."
cp "$BUILD_DIR/raytracer.exe" "$OUT_DIR/"
mkdir -p "$OUT_DIR/shaders"
cp "$BUILD_DIR/shaders/"*.spv "$OUT_DIR/shaders/"
cp "$PROJECT_DIR/README.md" "$OUT_DIR/"

cd "$BUILD_DIR"
zip -r "$PROJECT_DIR/VulkanRayTracer-Win64.zip" -j "$OUT_DIR/"*
# Re-do with directory structure
rm -f "$PROJECT_DIR/VulkanRayTracer-Win64.zip"
cd "$OUT_DIR" && zip -r "$PROJECT_DIR/VulkanRayTracer-Win64.zip" .

echo ""
echo "=== Done! ==="
echo "Windows build: $OUT_DIR/raytracer.exe"
echo "Package:       $PROJECT_DIR/VulkanRayTracer-Win64.zip"

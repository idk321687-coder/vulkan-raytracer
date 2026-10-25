// Vulkan Compute-Shader Path Tracer
// ---------------------------------------------------------------------------
// Renders a scene of spheres on a checkerboard floor using a GPU compute
// shader.  WASD + mouse to fly around, ESC to release/capture the cursor.
// ---------------------------------------------------------------------------

#include <vulkan/vulkan.h>
#include <GLFW/glfw3.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstring>
#include <fstream>
#include <iostream>
#include <limits>
#include <optional>
#include <set>
#include <stdexcept>
#include <vector>

// ===== Macros =====

#define VK_CHECK(call)                                                       \
    do {                                                                     \
        VkResult _r = (call);                                                \
        if (_r != VK_SUCCESS) {                                              \
            std::cerr << "Vulkan error " << _r << " at " << __FILE__         \
                      << ":" << __LINE__ << "\n";                            \
            throw std::runtime_error("Vulkan call failed");                  \
        }                                                                    \
    } while (0)

// ===== Constants =====

static constexpr uint32_t INIT_WIDTH  = 1280;
static constexpr uint32_t INIT_HEIGHT = 720;
static constexpr int MAX_FRAMES_IN_FLIGHT = 2;

#ifdef NDEBUG
static constexpr bool kEnableValidation = false;
#else
static constexpr bool kEnableValidation = true;
#endif

static const std::vector<const char*> kValidationLayers = {
    "VK_LAYER_KHRONOS_validation"
};

// ===== Push constants (must match shader layout) =====

struct PushConstants {
    float camPos[4];   // xyz = position,  w = time
    float camDir[4];   // xyz = forward,   w = aspect
    float camUp[4];    // xyz = up,        w = tan(fov/2)
    float camRight[4]; // xyz = right,     w = frame (uint bits)
};

// ===== Camera =====

struct Camera {
    float pos[3]     = {0.f, 2.f, 8.f};
    float yaw        = -90.f;   // degrees – facing -Z
    float pitch      = -10.f;
    float speed      = 4.f;
    float sensitivity = 0.12f;
    float fov        = 60.f;    // degrees

    void vectors(float* fwd, float* up, float* right) const {
        float yr = yaw   * 3.14159265f / 180.f;
        float pr = pitch * 3.14159265f / 180.f;
        fwd[0] = cosf(pr) * cosf(yr);
        fwd[1] = sinf(pr);
        fwd[2] = cosf(pr) * sinf(yr);
        float len = sqrtf(fwd[0]*fwd[0] + fwd[1]*fwd[1] + fwd[2]*fwd[2]);
        fwd[0] /= len;  fwd[1] /= len;  fwd[2] /= len;

        // right = fwd × world-up(0,1,0)
        right[0] = fwd[1]*0.f - fwd[2]*1.f;   //  -fwd[2]
        right[1] = fwd[2]*0.f - fwd[0]*0.f;   //   0
        right[2] = fwd[0]*1.f - fwd[1]*0.f;   //   fwd[0]
        // Simplify:
        right[0] = -fwd[2];
        right[1] = 0.f;
        right[2] = fwd[0];
        len = sqrtf(right[0]*right[0] + right[2]*right[2]);
        right[0] /= len;  right[2] /= len;

        // up = right × fwd
        up[0] = right[1]*fwd[2] - right[2]*fwd[1];
        up[1] = right[2]*fwd[0] - right[0]*fwd[2];
        up[2] = right[0]*fwd[1] - right[1]*fwd[0];
    }
};

// ===== Utility =====

static std::vector<char> readFile(const std::string& path) {
    std::ifstream f(path, std::ios::ate | std::ios::binary);
    if (!f.is_open()) throw std::runtime_error("Cannot open " + path);
    size_t sz = static_cast<size_t>(f.tellg());
    std::vector<char> buf(sz);
    f.seekg(0);
    f.read(buf.data(), sz);
    return buf;
}

static uint32_t findMemoryType(VkPhysicalDevice pd, uint32_t filter,
                               VkMemoryPropertyFlags props) {
    VkPhysicalDeviceMemoryProperties mem;
    vkGetPhysicalDeviceMemoryProperties(pd, &mem);
    for (uint32_t i = 0; i < mem.memoryTypeCount; i++) {
        if ((filter & (1 << i)) &&
            (mem.memoryTypes[i].propertyFlags & props) == props)
            return i;
    }
    throw std::runtime_error("No suitable memory type");
}

// =====================================================================
//  VulkanRayTracer
// =====================================================================

class VulkanRayTracer {
public:
    void run() {
        initWindow();
        initVulkan();
        mainLoop();
        cleanup();
    }

private:
    // ----- Window -----
    GLFWwindow* window_ = nullptr;
    bool framebufferResized_ = false;

    // ----- Instance / debug -----
    VkInstance       instance_       = VK_NULL_HANDLE;
    VkSurfaceKHR    surface_        = VK_NULL_HANDLE;

    // ----- Device -----
    VkPhysicalDevice physDev_        = VK_NULL_HANDLE;
    VkDevice         device_         = VK_NULL_HANDLE;
    uint32_t         queueFamily_    = 0;
    VkQueue          queue_          = VK_NULL_HANDLE;

    // ----- Swapchain -----
    VkSwapchainKHR           swapchain_      = VK_NULL_HANDLE;
    std::vector<VkImage>     swapImages_;
    VkFormat                 swapFormat_      = VK_FORMAT_B8G8R8A8_UNORM;
    VkExtent2D               swapExtent_     = {};

    // ----- Storage image (compute output) -----
    VkImage        storageImage_    = VK_NULL_HANDLE;
    VkDeviceMemory storageMem_      = VK_NULL_HANDLE;
    VkImageView    storageView_     = VK_NULL_HANDLE;

    // ----- Compute pipeline -----
    VkDescriptorSetLayout descLayout_   = VK_NULL_HANDLE;
    VkPipelineLayout      pipeLayout_   = VK_NULL_HANDLE;
    VkPipeline            pipeline_     = VK_NULL_HANDLE;

    // ----- Descriptors -----
    VkDescriptorPool descPool_  = VK_NULL_HANDLE;
    VkDescriptorSet  descSet_   = VK_NULL_HANDLE;

    // ----- Commands -----
    VkCommandPool                 cmdPool_ = VK_NULL_HANDLE;
    std::vector<VkCommandBuffer>  cmdBufs_;

    // ----- Sync -----
    std::vector<VkSemaphore> imageReady_;
    std::vector<VkSemaphore> renderDone_;
    std::vector<VkFence>     inFlight_;
    uint32_t currentFrame_ = 0;

    // ----- State -----
    Camera   cam_;
    uint32_t frameCount_ = 0;
    bool     mouseCaptured_ = false;
    double   lastMX_ = 0, lastMY_ = 0;
    bool     firstMouse_ = true;
    bool     keys_[512] = {};

    std::chrono::high_resolution_clock::time_point lastTime_;

    // =================================================================
    //  Window
    // =================================================================

    void initWindow() {
        glfwInit();
        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        window_ = glfwCreateWindow(INIT_WIDTH, INIT_HEIGHT,
                                   "Vulkan Ray Tracer", nullptr, nullptr);
        glfwSetWindowUserPointer(window_, this);
        glfwSetFramebufferSizeCallback(window_, fbResizeCB);
        glfwSetKeyCallback(window_, keyCB);
        glfwSetCursorPosCallback(window_, cursorCB);
        glfwSetMouseButtonCallback(window_, mouseBtnCB);

        // Start with captured cursor
        glfwSetInputMode(window_, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
        mouseCaptured_ = true;
    }

    static void fbResizeCB(GLFWwindow* w, int, int) {
        auto* app = reinterpret_cast<VulkanRayTracer*>(glfwGetWindowUserPointer(w));
        app->framebufferResized_ = true;
    }

    static void keyCB(GLFWwindow* w, int key, int, int action, int) {
        auto* app = reinterpret_cast<VulkanRayTracer*>(glfwGetWindowUserPointer(w));
        if (key < 0 || key >= 512) return;
        if (action == GLFW_PRESS)   app->keys_[key] = true;
        if (action == GLFW_RELEASE) app->keys_[key] = false;
        if (key == GLFW_KEY_ESCAPE && action == GLFW_PRESS) {
            app->mouseCaptured_ = !app->mouseCaptured_;
            glfwSetInputMode(w, GLFW_CURSOR,
                app->mouseCaptured_ ? GLFW_CURSOR_DISABLED : GLFW_CURSOR_NORMAL);
            app->firstMouse_ = true;
        }
    }

    static void cursorCB(GLFWwindow* w, double x, double y) {
        auto* app = reinterpret_cast<VulkanRayTracer*>(glfwGetWindowUserPointer(w));
        if (!app->mouseCaptured_) return;
        if (app->firstMouse_) {
            app->lastMX_ = x;
            app->lastMY_ = y;
            app->firstMouse_ = false;
            return;
        }
        float dx = static_cast<float>(x - app->lastMX_);
        float dy = static_cast<float>(app->lastMY_ - y);  // inverted
        app->lastMX_ = x;
        app->lastMY_ = y;
        app->cam_.yaw   += dx * app->cam_.sensitivity;
        app->cam_.pitch += dy * app->cam_.sensitivity;
        app->cam_.pitch = std::clamp(app->cam_.pitch, -89.f, 89.f);
    }

    static void mouseBtnCB(GLFWwindow* w, int button, int action, int) {
        auto* app = reinterpret_cast<VulkanRayTracer*>(glfwGetWindowUserPointer(w));
        if (button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_PRESS &&
            !app->mouseCaptured_) {
            app->mouseCaptured_ = true;
            glfwSetInputMode(w, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
            app->firstMouse_ = true;
        }
    }

    // =================================================================
    //  Vulkan initialisation
    // =================================================================

    void initVulkan() {
        createInstance();
        createSurface();
        pickPhysicalDevice();
        createLogicalDevice();
        createSwapchain();
        createStorageImage();
        createDescriptorSetLayout();
        createComputePipeline();
        createDescriptorPool();
        createDescriptorSet();
        createCommandPool();
        allocateCommandBuffers();
        createSyncObjects();
        lastTime_ = std::chrono::high_resolution_clock::now();
    }

    // ----- Instance -----

    void createInstance() {
        VkApplicationInfo appInfo{VK_STRUCTURE_TYPE_APPLICATION_INFO};
        appInfo.pApplicationName   = "VulkanRayTracer";
        appInfo.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
        appInfo.pEngineName        = "Custom";
        appInfo.engineVersion      = VK_MAKE_VERSION(1, 0, 0);
        appInfo.apiVersion         = VK_API_VERSION_1_2;

        // Extensions required by GLFW + optional debug
        uint32_t glfwExtCount = 0;
        const char** glfwExts = glfwGetRequiredInstanceExtensions(&glfwExtCount);
        std::vector<const char*> exts(glfwExts, glfwExts + glfwExtCount);

        VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
        ci.pApplicationInfo        = &appInfo;
        ci.enabledExtensionCount   = static_cast<uint32_t>(exts.size());
        ci.ppEnabledExtensionNames = exts.data();

        // Validation layers (debug builds) — only if actually available
        if (kEnableValidation) {
            uint32_t layerCount = 0;
            vkEnumerateInstanceLayerProperties(&layerCount, nullptr);
            std::vector<VkLayerProperties> available(layerCount);
            vkEnumerateInstanceLayerProperties(&layerCount, available.data());

            std::vector<const char*> activeLayers;
            for (auto* wanted : kValidationLayers) {
                for (auto& l : available) {
                    if (strcmp(l.layerName, wanted) == 0) {
                        activeLayers.push_back(wanted);
                        break;
                    }
                }
            }
            if (!activeLayers.empty()) {
                ci.enabledLayerCount   = static_cast<uint32_t>(activeLayers.size());
                ci.ppEnabledLayerNames = activeLayers.data();
            }
        }

        VK_CHECK(vkCreateInstance(&ci, nullptr, &instance_));
    }

    // ----- Surface -----

    void createSurface() {
        VK_CHECK(glfwCreateWindowSurface(instance_, window_, nullptr, &surface_));
    }

    // ----- Physical device -----

    void pickPhysicalDevice() {
        uint32_t count = 0;
        vkEnumeratePhysicalDevices(instance_, &count, nullptr);
        if (count == 0) throw std::runtime_error("No Vulkan GPU found");
        std::vector<VkPhysicalDevice> devs(count);
        vkEnumeratePhysicalDevices(instance_, &count, devs.data());

        // Score devices – prefer discrete GPU
        int bestScore = -1;
        for (auto& pd : devs) {
            VkPhysicalDeviceProperties props;
            vkGetPhysicalDeviceProperties(pd, &props);

            // Need a queue family with compute + present
            uint32_t qfCount = 0;
            vkGetPhysicalDeviceQueueFamilyProperties(pd, &qfCount, nullptr);
            std::vector<VkQueueFamilyProperties> qfs(qfCount);
            vkGetPhysicalDeviceQueueFamilyProperties(pd, &qfCount, qfs.data());

            int family = -1;
            for (uint32_t i = 0; i < qfCount; i++) {
                VkBool32 present = VK_FALSE;
                vkGetPhysicalDeviceSurfaceSupportKHR(pd, i, surface_, &present);
                if ((qfs[i].queueFlags & VK_QUEUE_COMPUTE_BIT) && present) {
                    family = static_cast<int>(i);
                    break;
                }
            }
            if (family < 0) continue;

            // Check swapchain support
            uint32_t extCount = 0;
            vkEnumerateDeviceExtensionProperties(pd, nullptr, &extCount, nullptr);
            std::vector<VkExtensionProperties> extProps(extCount);
            vkEnumerateDeviceExtensionProperties(pd, nullptr, &extCount, extProps.data());
            bool hasSwap = false;
            for (auto& e : extProps)
                if (strcmp(e.extensionName, VK_KHR_SWAPCHAIN_EXTENSION_NAME) == 0)
                    hasSwap = true;
            if (!hasSwap) continue;

            int score = 1;
            if (props.deviceType == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) score += 1000;

            if (score > bestScore) {
                bestScore   = score;
                physDev_    = pd;
                queueFamily_ = static_cast<uint32_t>(family);
            }
        }
        if (physDev_ == VK_NULL_HANDLE)
            throw std::runtime_error("No suitable GPU found");

        VkPhysicalDeviceProperties props;
        vkGetPhysicalDeviceProperties(physDev_, &props);
        std::cout << "GPU: " << props.deviceName << "\n";
    }

    // ----- Logical device -----

    void createLogicalDevice() {
        float priority = 1.f;
        VkDeviceQueueCreateInfo qci{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
        qci.queueFamilyIndex = queueFamily_;
        qci.queueCount       = 1;
        qci.pQueuePriorities = &priority;

        const char* devExts[] = { VK_KHR_SWAPCHAIN_EXTENSION_NAME };

        VkPhysicalDeviceFeatures features{};

        VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
        dci.queueCreateInfoCount    = 1;
        dci.pQueueCreateInfos       = &qci;
        dci.enabledExtensionCount   = 1;
        dci.ppEnabledExtensionNames = devExts;
        dci.pEnabledFeatures        = &features;

        VK_CHECK(vkCreateDevice(physDev_, &dci, nullptr, &device_));
        vkGetDeviceQueue(device_, queueFamily_, 0, &queue_);
    }

    // ----- Swapchain -----

    void createSwapchain() {
        VkSurfaceCapabilitiesKHR caps;
        vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physDev_, surface_, &caps);

        // Pick format
        uint32_t fmtCount = 0;
        vkGetPhysicalDeviceSurfaceFormatsKHR(physDev_, surface_, &fmtCount, nullptr);
        std::vector<VkSurfaceFormatKHR> fmts(fmtCount);
        vkGetPhysicalDeviceSurfaceFormatsKHR(physDev_, surface_, &fmtCount, fmts.data());

        swapFormat_ = fmts[0].format;
        VkColorSpaceKHR colorSpace = fmts[0].colorSpace;
        for (auto& f : fmts) {
            if ((f.format == VK_FORMAT_B8G8R8A8_SRGB ||
                 f.format == VK_FORMAT_B8G8R8A8_UNORM) &&
                f.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                swapFormat_ = f.format;
                colorSpace  = f.colorSpace;
                break;
            }
        }

        // Pick present mode – prefer MAILBOX, fall back to FIFO
        uint32_t pmCount = 0;
        vkGetPhysicalDeviceSurfacePresentModesKHR(physDev_, surface_, &pmCount, nullptr);
        std::vector<VkPresentModeKHR> pms(pmCount);
        vkGetPhysicalDeviceSurfacePresentModesKHR(physDev_, surface_, &pmCount, pms.data());
        VkPresentModeKHR presentMode = VK_PRESENT_MODE_FIFO_KHR;
        for (auto m : pms)
            if (m == VK_PRESENT_MODE_MAILBOX_KHR) { presentMode = m; break; }

        // Extent
        if (caps.currentExtent.width != UINT32_MAX) {
            swapExtent_ = caps.currentExtent;
        } else {
            int w, h;
            glfwGetFramebufferSize(window_, &w, &h);
            swapExtent_.width  = std::clamp(static_cast<uint32_t>(w),
                                            caps.minImageExtent.width,
                                            caps.maxImageExtent.width);
            swapExtent_.height = std::clamp(static_cast<uint32_t>(h),
                                            caps.minImageExtent.height,
                                            caps.maxImageExtent.height);
        }

        uint32_t imgCount = caps.minImageCount + 1;
        if (caps.maxImageCount > 0)
            imgCount = std::min(imgCount, caps.maxImageCount);

        VkSwapchainCreateInfoKHR sci{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
        sci.surface          = surface_;
        sci.minImageCount    = imgCount;
        sci.imageFormat      = swapFormat_;
        sci.imageColorSpace  = colorSpace;
        sci.imageExtent      = swapExtent_;
        sci.imageArrayLayers = 1;
        sci.imageUsage       = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
                             | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        sci.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
        sci.preTransform     = caps.currentTransform;
        sci.compositeAlpha   = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
        sci.presentMode      = presentMode;
        sci.clipped          = VK_TRUE;
        sci.oldSwapchain     = VK_NULL_HANDLE;

        VK_CHECK(vkCreateSwapchainKHR(device_, &sci, nullptr, &swapchain_));

        vkGetSwapchainImagesKHR(device_, swapchain_, &imgCount, nullptr);
        swapImages_.resize(imgCount);
        vkGetSwapchainImagesKHR(device_, swapchain_, &imgCount, swapImages_.data());
    }

    // ----- Storage image -----

    void createStorageImage() {
        VkImageCreateInfo ici{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
        ici.imageType     = VK_IMAGE_TYPE_2D;
        ici.format        = VK_FORMAT_R8G8B8A8_UNORM;
        ici.extent        = {swapExtent_.width, swapExtent_.height, 1};
        ici.mipLevels     = 1;
        ici.arrayLayers   = 1;
        ici.samples       = VK_SAMPLE_COUNT_1_BIT;
        ici.tiling        = VK_IMAGE_TILING_OPTIMAL;
        ici.usage         = VK_IMAGE_USAGE_STORAGE_BIT
                          | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        ici.sharingMode   = VK_SHARING_MODE_EXCLUSIVE;
        ici.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

        VK_CHECK(vkCreateImage(device_, &ici, nullptr, &storageImage_));

        VkMemoryRequirements memReq;
        vkGetImageMemoryRequirements(device_, storageImage_, &memReq);

        VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        ai.allocationSize  = memReq.size;
        ai.memoryTypeIndex = findMemoryType(physDev_, memReq.memoryTypeBits,
                                            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);

        VK_CHECK(vkAllocateMemory(device_, &ai, nullptr, &storageMem_));
        VK_CHECK(vkBindImageMemory(device_, storageImage_, storageMem_, 0));

        // Image view
        VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
        vi.image    = storageImage_;
        vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
        vi.format   = VK_FORMAT_R8G8B8A8_UNORM;
        vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};

        VK_CHECK(vkCreateImageView(device_, &vi, nullptr, &storageView_));
    }

    // ----- Descriptor set layout -----

    void createDescriptorSetLayout() {
        VkDescriptorSetLayoutBinding binding{};
        binding.binding         = 0;
        binding.descriptorType  = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        binding.descriptorCount = 1;
        binding.stageFlags      = VK_SHADER_STAGE_COMPUTE_BIT;

        VkDescriptorSetLayoutCreateInfo ci{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
        ci.bindingCount = 1;
        ci.pBindings    = &binding;

        VK_CHECK(vkCreateDescriptorSetLayout(device_, &ci, nullptr, &descLayout_));
    }

    // ----- Compute pipeline -----

    void createComputePipeline() {
        auto code = readFile("shaders/raytrace.comp.spv");

        VkShaderModuleCreateInfo smci{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
        smci.codeSize = code.size();
        smci.pCode    = reinterpret_cast<const uint32_t*>(code.data());

        VkShaderModule shaderMod;
        VK_CHECK(vkCreateShaderModule(device_, &smci, nullptr, &shaderMod));

        VkPushConstantRange pcRange{};
        pcRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        pcRange.offset     = 0;
        pcRange.size       = sizeof(PushConstants);

        VkPipelineLayoutCreateInfo plci{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
        plci.setLayoutCount         = 1;
        plci.pSetLayouts            = &descLayout_;
        plci.pushConstantRangeCount = 1;
        plci.pPushConstantRanges    = &pcRange;

        VK_CHECK(vkCreatePipelineLayout(device_, &plci, nullptr, &pipeLayout_));

        VkPipelineShaderStageCreateInfo stage{VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};
        stage.stage  = VK_SHADER_STAGE_COMPUTE_BIT;
        stage.module = shaderMod;
        stage.pName  = "main";

        VkComputePipelineCreateInfo cpci{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
        cpci.stage  = stage;
        cpci.layout = pipeLayout_;

        VK_CHECK(vkCreateComputePipelines(device_, VK_NULL_HANDLE, 1, &cpci, nullptr, &pipeline_));

        vkDestroyShaderModule(device_, shaderMod, nullptr);
    }

    // ----- Descriptor pool + set -----

    void createDescriptorPool() {
        VkDescriptorPoolSize poolSize{};
        poolSize.type            = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        poolSize.descriptorCount = 1;

        VkDescriptorPoolCreateInfo ci{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
        ci.maxSets       = 1;
        ci.poolSizeCount = 1;
        ci.pPoolSizes    = &poolSize;

        VK_CHECK(vkCreateDescriptorPool(device_, &ci, nullptr, &descPool_));
    }

    void createDescriptorSet() {
        VkDescriptorSetAllocateInfo ai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
        ai.descriptorPool     = descPool_;
        ai.descriptorSetCount = 1;
        ai.pSetLayouts        = &descLayout_;

        VK_CHECK(vkAllocateDescriptorSets(device_, &ai, &descSet_));

        VkDescriptorImageInfo imgInfo{};
        imgInfo.imageView   = storageView_;
        imgInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;

        VkWriteDescriptorSet write{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
        write.dstSet          = descSet_;
        write.dstBinding      = 0;
        write.descriptorCount = 1;
        write.descriptorType  = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        write.pImageInfo      = &imgInfo;

        vkUpdateDescriptorSets(device_, 1, &write, 0, nullptr);
    }

    // ----- Commands -----

    void createCommandPool() {
        VkCommandPoolCreateInfo ci{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
        ci.flags            = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        ci.queueFamilyIndex = queueFamily_;
        VK_CHECK(vkCreateCommandPool(device_, &ci, nullptr, &cmdPool_));
    }

    void allocateCommandBuffers() {
        cmdBufs_.resize(MAX_FRAMES_IN_FLIGHT);
        VkCommandBufferAllocateInfo ai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
        ai.commandPool        = cmdPool_;
        ai.level              = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        ai.commandBufferCount = MAX_FRAMES_IN_FLIGHT;
        VK_CHECK(vkAllocateCommandBuffers(device_, &ai, cmdBufs_.data()));
    }

    // ----- Sync objects -----

    void createSyncObjects() {
        imageReady_.resize(MAX_FRAMES_IN_FLIGHT);
        renderDone_.resize(MAX_FRAMES_IN_FLIGHT);
        inFlight_.resize(MAX_FRAMES_IN_FLIGHT);

        VkSemaphoreCreateInfo sci{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
        VkFenceCreateInfo fci{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
        fci.flags = VK_FENCE_CREATE_SIGNALED_BIT;

        for (int i = 0; i < MAX_FRAMES_IN_FLIGHT; i++) {
            VK_CHECK(vkCreateSemaphore(device_, &sci, nullptr, &imageReady_[i]));
            VK_CHECK(vkCreateSemaphore(device_, &sci, nullptr, &renderDone_[i]));
            VK_CHECK(vkCreateFence(device_, &fci, nullptr, &inFlight_[i]));
        }
    }

    // =================================================================
    //  Command buffer recording
    // =================================================================

    void recordCommandBuffer(VkCommandBuffer cmd, uint32_t imageIndex) {
        VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        VK_CHECK(vkBeginCommandBuffer(cmd, &bi));

        // --- 1. Transition storage image → GENERAL ---
        {
            VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
            b.srcAccessMask       = 0;
            b.dstAccessMask       = VK_ACCESS_SHADER_WRITE_BIT;
            b.oldLayout           = VK_IMAGE_LAYOUT_UNDEFINED;
            b.newLayout           = VK_IMAGE_LAYOUT_GENERAL;
            b.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            b.image               = storageImage_;
            b.subresourceRange    = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};

            vkCmdPipelineBarrier(cmd,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                0, 0, nullptr, 0, nullptr, 1, &b);
        }

        // --- 2. Dispatch compute ---
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
                                pipeLayout_, 0, 1, &descSet_, 0, nullptr);

        // Build push constants
        auto now = std::chrono::high_resolution_clock::now();
        float time = std::chrono::duration<float>(now - lastTime_).count()
                   + static_cast<float>(frameCount_) * 0.001f;  // monotonic

        float fwd[3], up[3], right[3];
        cam_.vectors(fwd, up, right);

        PushConstants pc{};
        pc.camPos[0] = cam_.pos[0];  pc.camPos[1] = cam_.pos[1];
        pc.camPos[2] = cam_.pos[2];  pc.camPos[3] = time;
        pc.camDir[0] = fwd[0];       pc.camDir[1] = fwd[1];
        pc.camDir[2] = fwd[2];       pc.camDir[3] = static_cast<float>(swapExtent_.width) /
                                                     static_cast<float>(swapExtent_.height);
        pc.camUp[0]  = up[0];        pc.camUp[1]  = up[1];
        pc.camUp[2]  = up[2];        pc.camUp[3]  = tanf(cam_.fov * 0.5f * 3.14159265f / 180.f);
        pc.camRight[0] = right[0];   pc.camRight[1] = right[1];
        pc.camRight[2] = right[2];

        // Pack frame count as float (bit-preserving uint→float)
        uint32_t fc = frameCount_;
        float fcf;
        memcpy(&fcf, &fc, sizeof(float));
        pc.camRight[3] = fcf;

        vkCmdPushConstants(cmd, pipeLayout_, VK_SHADER_STAGE_COMPUTE_BIT,
                           0, sizeof(PushConstants), &pc);

        uint32_t gx = (swapExtent_.width  + 15) / 16;
        uint32_t gy = (swapExtent_.height + 15) / 16;
        vkCmdDispatch(cmd, gx, gy, 1);

        // --- 3. Barrier: storage image → TRANSFER_SRC ---
        {
            VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
            b.srcAccessMask       = VK_ACCESS_SHADER_WRITE_BIT;
            b.dstAccessMask       = VK_ACCESS_TRANSFER_READ_BIT;
            b.oldLayout           = VK_IMAGE_LAYOUT_GENERAL;
            b.newLayout           = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
            b.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            b.image               = storageImage_;
            b.subresourceRange    = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};

            vkCmdPipelineBarrier(cmd,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                0, 0, nullptr, 0, nullptr, 1, &b);
        }

        // --- 4. Barrier: swapchain image → TRANSFER_DST ---
        {
            VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
            b.srcAccessMask       = 0;
            b.dstAccessMask       = VK_ACCESS_TRANSFER_WRITE_BIT;
            b.oldLayout           = VK_IMAGE_LAYOUT_UNDEFINED;
            b.newLayout           = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
            b.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            b.image               = swapImages_[imageIndex];
            b.subresourceRange    = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};

            vkCmdPipelineBarrier(cmd,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                0, 0, nullptr, 0, nullptr, 1, &b);
        }

        // --- 5. Blit storage image → swapchain image ---
        VkImageBlit region{};
        region.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        region.srcOffsets[0]  = {0, 0, 0};
        region.srcOffsets[1]  = {static_cast<int32_t>(swapExtent_.width),
                                 static_cast<int32_t>(swapExtent_.height), 1};
        region.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        region.dstOffsets[0]  = {0, 0, 0};
        region.dstOffsets[1]  = {static_cast<int32_t>(swapExtent_.width),
                                 static_cast<int32_t>(swapExtent_.height), 1};

        vkCmdBlitImage(cmd,
            storageImage_,              VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            swapImages_[imageIndex],    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            1, &region, VK_FILTER_NEAREST);

        // --- 6. Barrier: swapchain image → PRESENT_SRC ---
        {
            VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
            b.srcAccessMask       = VK_ACCESS_TRANSFER_WRITE_BIT;
            b.dstAccessMask       = 0;
            b.oldLayout           = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
            b.newLayout           = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
            b.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            b.image               = swapImages_[imageIndex];
            b.subresourceRange    = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};

            vkCmdPipelineBarrier(cmd,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                0, 0, nullptr, 0, nullptr, 1, &b);
        }

        VK_CHECK(vkEndCommandBuffer(cmd));
    }

    // =================================================================
    //  Draw frame
    // =================================================================

    void drawFrame() {
        vkWaitForFences(device_, 1, &inFlight_[currentFrame_], VK_TRUE, UINT64_MAX);

        uint32_t imageIndex;
        VkResult result = vkAcquireNextImageKHR(device_, swapchain_, UINT64_MAX,
                                                imageReady_[currentFrame_],
                                                VK_NULL_HANDLE, &imageIndex);

        if (result == VK_ERROR_OUT_OF_DATE_KHR) {
            recreateSwapchain();
            return;
        } else if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) {
            throw std::runtime_error("Failed to acquire swapchain image");
        }

        vkResetFences(device_, 1, &inFlight_[currentFrame_]);

        VkCommandBuffer cmd = cmdBufs_[currentFrame_];
        vkResetCommandBuffer(cmd, 0);
        recordCommandBuffer(cmd, imageIndex);

        VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;

        VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        si.waitSemaphoreCount   = 1;
        si.pWaitSemaphores      = &imageReady_[currentFrame_];
        si.pWaitDstStageMask    = &waitStage;
        si.commandBufferCount   = 1;
        si.pCommandBuffers      = &cmd;
        si.signalSemaphoreCount = 1;
        si.pSignalSemaphores    = &renderDone_[currentFrame_];

        VK_CHECK(vkQueueSubmit(queue_, 1, &si, inFlight_[currentFrame_]));

        VkPresentInfoKHR pi{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
        pi.waitSemaphoreCount = 1;
        pi.pWaitSemaphores    = &renderDone_[currentFrame_];
        pi.swapchainCount     = 1;
        pi.pSwapchains        = &swapchain_;
        pi.pImageIndices      = &imageIndex;

        result = vkQueuePresentKHR(queue_, &pi);
        if (result == VK_ERROR_OUT_OF_DATE_KHR || result == VK_SUBOPTIMAL_KHR ||
            framebufferResized_) {
            framebufferResized_ = false;
            recreateSwapchain();
        } else if (result != VK_SUCCESS) {
            throw std::runtime_error("Failed to present");
        }

        currentFrame_ = (currentFrame_ + 1) % MAX_FRAMES_IN_FLIGHT;
        frameCount_++;
    }

    // =================================================================
    //  Swapchain recreation
    // =================================================================

    void cleanupSwapchain() {
        vkDestroyImageView(device_, storageView_, nullptr);
        vkDestroyImage(device_, storageImage_, nullptr);
        vkFreeMemory(device_, storageMem_, nullptr);
        vkDestroySwapchainKHR(device_, swapchain_, nullptr);
    }

    void recreateSwapchain() {
        // Handle minimisation
        int w = 0, h = 0;
        glfwGetFramebufferSize(window_, &w, &h);
        while (w == 0 || h == 0) {
            glfwGetFramebufferSize(window_, &w, &h);
            glfwWaitEvents();
        }

        vkDeviceWaitIdle(device_);
        cleanupSwapchain();

        createSwapchain();
        createStorageImage();

        // Re-bind descriptor to new storage image view
        vkResetDescriptorPool(device_, descPool_, 0);
        createDescriptorSet();
    }

    // =================================================================
    //  Main loop
    // =================================================================

    void mainLoop() {
        auto prevTime = std::chrono::high_resolution_clock::now();

        while (!glfwWindowShouldClose(window_)) {
            glfwPollEvents();

            // Delta time
            auto now = std::chrono::high_resolution_clock::now();
            float dt = std::chrono::duration<float>(now - prevTime).count();
            prevTime = now;

            // Camera movement
            float fwd[3], up[3], right[3];
            cam_.vectors(fwd, up, right);
            float v = cam_.speed * dt;

            if (keys_[GLFW_KEY_W]) {
                cam_.pos[0] += fwd[0] * v;
                cam_.pos[1] += fwd[1] * v;
                cam_.pos[2] += fwd[2] * v;
            }
            if (keys_[GLFW_KEY_S]) {
                cam_.pos[0] -= fwd[0] * v;
                cam_.pos[1] -= fwd[1] * v;
                cam_.pos[2] -= fwd[2] * v;
            }
            if (keys_[GLFW_KEY_A]) {
                cam_.pos[0] -= right[0] * v;
                cam_.pos[2] -= right[2] * v;
            }
            if (keys_[GLFW_KEY_D]) {
                cam_.pos[0] += right[0] * v;
                cam_.pos[2] += right[2] * v;
            }
            if (keys_[GLFW_KEY_SPACE])       cam_.pos[1] += v;
            if (keys_[GLFW_KEY_LEFT_SHIFT])  cam_.pos[1] -= v;

            drawFrame();
        }

        vkDeviceWaitIdle(device_);
    }

    // =================================================================
    //  Cleanup
    // =================================================================

    void cleanup() {
        for (int i = 0; i < MAX_FRAMES_IN_FLIGHT; i++) {
            vkDestroySemaphore(device_, imageReady_[i], nullptr);
            vkDestroySemaphore(device_, renderDone_[i], nullptr);
            vkDestroyFence(device_, inFlight_[i], nullptr);
        }
        vkDestroyCommandPool(device_, cmdPool_, nullptr);
        vkDestroyDescriptorPool(device_, descPool_, nullptr);
        vkDestroyPipeline(device_, pipeline_, nullptr);
        vkDestroyPipelineLayout(device_, pipeLayout_, nullptr);
        vkDestroyDescriptorSetLayout(device_, descLayout_, nullptr);
        cleanupSwapchain();
        vkDestroyDevice(device_, nullptr);
        vkDestroySurfaceKHR(instance_, surface_, nullptr);
        vkDestroyInstance(instance_, nullptr);
        glfwDestroyWindow(window_);
        glfwTerminate();
    }
};

// =====================================================================
//  Entry point
// =====================================================================

int main() {
    try {
        VulkanRayTracer app;
        app.run();
    } catch (const std::exception& e) {
        std::cerr << "FATAL: " << e.what() << "\n";
        return EXIT_FAILURE;
    }
    return EXIT_SUCCESS;
}


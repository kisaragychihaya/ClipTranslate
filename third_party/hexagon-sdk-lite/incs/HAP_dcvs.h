/*-----------------------------------------------------------------------------
   Copyright (c) 2021, 2022 QUALCOMM Technologies, Incorporated.
   All Rights Reserved.
   QUALCOMM Proprietary.
-----------------------------------------------------------------------------*/

#ifndef HAP_DCVS_H_
#define HAP_DCVS_H_

/**
 *  @file HAP_dcvs.h
 *  @brief Header file for DCVS APIs.
 */

#include "AEEStdErr.h"
#include "HAP_power.h"

#ifdef __cplusplus
extern "C" {
#endif

#define HAP_DCVS_EXP_VCORNER_VERSION             1
/**< Version info for HAP_dcvs_exp_vcorner_t structure */

#define HAP_DCVS_CAPABILITY_VERSION              1
/**< Version info for HAP_dcvs_capability_t  structure */

#define HAP_DCVS_CENG_EXP_PERF_MODE_VERSION      1
/**< Version info for HAP_dcvs_capability_t  structure */

#define HAP_DCVS_HMX_EXP_PERF_MODE_VERSION       1
/**< Version info for HAP_dcvs_capability_t  structure */


/**
 * @start Defined Capabilities
 */
#define HAP_DCVS_EXP_VCORNER_PERFMODE_SUPPORT                1U
/**< Indicates support for
 * Multiple levels of perf mode feature for Core, Bus clock and
 * Expanded voltage corner levels for Core, Bus and CENG clock
 */

#define HAP_DCVS_NOTIFY_SUPPORT                              2U
/**< Indicates support for DCVS Notify feature
 */

#define HAP_DCVS_BUS_SYS_STATE_SUPPORT                       4U
/**< Indicates support for Bus Sys State Voting Feature
 */

 #define HAP_DCVS_EXP_PERFMODE_SUPPORT_FOR_CENG_HMX          8U
/**< Indicates support for
 * Multiple levels of perf mode feature for CENG and HMX Clock
 */

 /**
 * @end Defined Capabilities
 */

/**
 *  Perf modes to specify CORE/BUS clock frequency level within
 *  target voltage corner for HAP DCVS V3 interface.
 */
typedef enum
{
    HAP_DCVS_CLK_PERF_HIGH,
    /**< To select max frequency at target voltage corner */

    HAP_DCVS_CLK_PERF_LOW,
    /**< To select minimum frequency at target voltage corner */

    HAP_DCVS_CLK_PERF_HIGH_D1,
    /**< To select one level down from max frequency at target voltage corner */

    HAP_DCVS_CLK_PERF_HIGH_D2,
    /**< To select two levels down from max frequency at target voltage corner */

    HAP_DCVS_CLK_PERF_HIGH_D3,
    /**< To select three levels down from max frequency at target voltage corner */

    HAP_DCVS_CLK_PERF_HIGH_D4,
    /**< To select four levels down from max frequency at target voltage corner */

    HAP_DCVS_CLK_PERF_HIGH_D5,
    /**< To select five levels down from max frequency at target voltage corner */

    HAP_DCVS_CLK_PERF_HIGH_D6,
    /**< To select six levels down from max frequency at target voltage corner */

    HAP_DCVS_CLK_PERF_HIGH_D7,
    /**< To select seven levels down from max frequency at target voltage corner */

    HAP_DCVS_CLK_PERF_HIGH_D8,
    /**< To select eight levels down from max frequency at target voltage corner */

    HAP_DCVS_CLK_PERF_HIGH_D9,
    /**< To select nine levels down from max frequency at target voltage corner */

    HAP_DCVS_CLK_PERF_HIGH_D10,
    /**< To select ten levels down from max frequency at target voltage corner */

    HAP_DCVS_CLK_PERF_HIGH_D11,
    /**< To select eleven levels down from max frequency at target voltage corner */

    HAP_DCVS_CLK_PERF_HIGH_D12,
    /**< To select twelve levels down from max frequency at target voltage corner */

    HAP_DCVS_CLK_PERF_HIGH_D13,
    /**< To select thirteen levels down from max frequency at target voltage corner */

    HAP_DCVS_CLK_PERF_HIGH_D14,
    /**< To select fourteen levels down from max frequency at target voltage corner */

    HAP_DCVS_CLK_PERF_HIGH_D15,
    /**< To select fifteen levels down from max frequency at target voltage corner */

    HAP_DCVS_CLK_PERF_DEFINED_MAX = HAP_DCVS_CLK_PERF_HIGH_D15,

    HAP_DCVS_CLK_PERF_MAX = 0xFF,
    /**< Same behavior as HAP_DCVS_CLK_PERF_LOW; selects minimum frequency at the target voltage corner */
} HAP_dcvs_clk_perf_mode_t;

/**
 *  @enum HAP_dcvs_request_type_t
 *  @brief DCVS request type
 */
typedef enum
{
    HAP_DCVS_CAPABILITY_QUERY = 1,
    /**< Provide DCVS Capability Info */

    HAP_DCVS_SET_EXP_VCORNERS,
    /**< To set the expanded voltage corners */

    HAP_DCVS_SET_BUS_SYS_STATE,
    /**< To set the Bus System State */

    HAP_DCVS_NOTIFY_REG,
    /**< To register for DCVS Notify */

    HAP_DCVS_NOTIFY_EVENT,
    /**< To send DCVS Notify event */

    HAP_DCVS_SET_HMX_PERF_MODE,
    /**< To set the expanded perf mode to HMX Clock */

    HAP_DCVS_SET_CENG_PERF_MODE,
    /**< To set the expanded perf mode to CENG Clock */
} HAP_dcvs_request_type_t;

/**
 *  @struct HAP_dcvs_exp_vcorner_params_t
 *  @brief Structure to hold expanded voltage corners
 */
typedef struct
{
    unsigned int target_corner;
    /**< Client's request for the target voltage corner */

    unsigned int min_corner;
    /**< Client's request for minimum voltage corner */

    unsigned int max_corner;
    /**< Client's request for maximum voltage corner */
} HAP_dcvs_exp_vcorner_params_t;

/**
 *  @struct HAP_dcvs_exp_vcorner_t
 *  @brief Structure to hold core, bus and ceng
 *  expanded voltage corners
 */
typedef struct
{
    unsigned int version;
    /**< Version (Updated by DCVS) */

    unsigned int size;
    /**< Size of Structure (Updated by DCVS) */

    unsigned int set_core_vcorner;
    /**< Master flag for core params, 1 to consider the "core_params" else 0 */

    HAP_dcvs_exp_vcorner_params_t core_params;
    /**< Holds the core params if "set_core_vcorner" is set */

    unsigned int set_bus_vcorner;
    /**< Master flag for bus params, 1 to consider the "bus_params" else 0 */

    HAP_dcvs_exp_vcorner_params_t bus_params;
    /**< Holds the bus params if "set_bus_vcorner" is set */

    unsigned int set_ceng_vcorner;
    /**< Master flag for ceng params, 1 to consider the "ceng_params" else 0 */

    HAP_dcvs_exp_vcorner_params_t ceng_params;
    /**< Holds the ceng params if "set_ceng_corners" is set */
} HAP_dcvs_exp_vcorner_t;


/**
 * @start DCVS Notify
 */
/**
 *  @struct HAP_dcvs_notify_reg_t
 *  @brief Structure to hold DCVS notify registration params
 */
typedef struct
{
    unsigned int version;
    /**< Version (Updated by DCVS) */

    unsigned int size;
    /**< Size of Structure (Updated by DCVS) */

    unsigned int enable;
    /**< Set to 1 for register, 0 for de-register */

    unsigned int fps;
    /**< FPS if activity is periodic (Optional) */

    unsigned int timeline;
    /**< Max frame time allowed in msec */

    unsigned int up_frame_count;
    /**< No.of Frames to take clock UP decision
     * Default will be 1 if not specified.
     */

    unsigned int down_frame_count;
    /**< No.of Frames to take clock DOWN decision
     * Default will be 1 if not specified.
     */

    unsigned int core_step_count;
    /**< No. of core levels to be
     * UP( when timeline miss) or
     * DOWN(when timeline less than enough)
     * Default will be 1 if not specified i.e. 0
     * Note: UP/Down levels will be within the min and max
     * corner levels specified during HAP_power_set */

    unsigned int bus_step_count;
    /**< No. of bus levels to be
     * UP( when timeline miss) or
     * DOWN(when timeline less than enough)
     * Default will be 1 if not specified i.e. 0
     * Note: UP/Down levels will be within the min and max
     * corner levels specified during HAP_power_set */

    unsigned int timeline_down_threshold;
    /**< Threshold percentage of "timeline" param
     * below which clock down decision will happen.
     * DCVS will decide if not specified. */
} HAP_dcvs_notify_reg_t;

/**
 *  @enum HAP_dcvs_notify_event_type_t
 *  @brief DCVS notify event types
 */
typedef enum
{
    HAP_DCVS_ACTIVITY_START,
    /**< Activity Start event */

    HAP_DCVS_ACTIVITY_STOP
    /**< Activity Stop event */
} HAP_dcvs_notify_event_type_t;

/**
 *  @struct HAP_dcvs_notify_event_t
 *  @brief Structure to hold DCVS notify event type params
 */
typedef struct
{
    unsigned int version;
    /**< Version (Updated by DCVS) */

    unsigned int size;
    /**< Size of Structure (Updated by DCVS) */

    HAP_dcvs_notify_event_type_t event;
    /**< Notify Event type */
} HAP_dcvs_notify_event_t;
/**
 * @end DCVS Notify
 */

/**
 *  @struct HAP_dcvs_capability_t
 *  @brief Structure to hold DCVS Capability Query Params
 */
typedef struct
{
    unsigned int version;
    /**< Version (Updated by DCVS) */

    unsigned int size;
    /**< Size of Structure (Updated by DCVS) */

    unsigned long long capability_info;
    /**<  Each bit represents specific capability */
} HAP_dcvs_capability_t;

/**
 *  @struct HAP_dcvs_bus_sys_state_t
 *  @brief Structure to hold bus system state params
 */
typedef struct
{
    unsigned int version;
    /**< Version (Updated by DCVS) */

    unsigned int size;
    /**< Size of Structure (Updated by DCVS) */

    unsigned int system_state;
    /**< To select the bus system state at given DDR frequency
     * 0 (Default) - Lowest system state
     * 1..SYSMON_DCVS_MAX_BUS_SYS_STATE - System state in increasing order */
} HAP_dcvs_bus_sys_state_t;


/**
 *  @struct HAP_dcvs_exp_perf_mode_t
 *  @brief Structure to hold CENG/HMX Clock perf mode info.
 */
typedef struct
{
    unsigned int version;
    /**< Version (Updated by DCVS) */

    unsigned int size;
    /**< Size of Structure (Updated by DCVS) */

    unsigned int perf_mode;
    /**< Perf mode to specify CENG/HMX clock frequency level within target voltage corner */

} HAP_dcvs_exp_perf_mode_t;

/**
 * @struct HAP_dcvs_request_t
 * Generic DCVS request container.
 * - type            : Identifies which request/union member is valid.
 * - notify_reg      : Register/unregister for DCVS notifications.
 * - notify_event    : Notification event information.
 * - exp_vcorners    : Expected voltage corner settings.
 * - capability      : DCVS capability query.
 * - bus_state       : Bus system state information.
 * - hmx_perf_mode   : HMX Clock expanded perf mode setting.
 * - ceng_perf_mode  : CENG Clock expanded perf mode setting.
 */
typedef struct
{
    HAP_dcvs_request_type_t type;
    union
    {
        HAP_dcvs_notify_reg_t notify_reg;
        HAP_dcvs_notify_event_t notify_event;
        HAP_dcvs_exp_vcorner_t exp_vcorners;
        HAP_dcvs_capability_t capability;
        HAP_dcvs_bus_sys_state_t bus_state;
        HAP_dcvs_exp_perf_mode_t hmx_perf_mode;
        HAP_dcvs_exp_perf_mode_t ceng_perf_mode;
    };
} HAP_dcvs_request_t;

/**
 * @cond DEV
 */
int __attribute__((weak)) sysmon_set_dcvs_v3_duty_cycle(
                                        void* context,
                                        uint32 max_active_time,
                                        uint32 periodicity);

int __attribute__((weak)) sysmon_set_dcvs_v3_duty_cycle_params(
                                                HAP_power_request_t* request,
                                                uint32 max_active_time,
                                                uint32 periodicity);

int __attribute__((weak)) sysmon_set_dcvs_v3_core_perf_mode(
                                        HAP_power_request_t* request,
                                        HAP_dcvs_clk_perf_mode_t perf_mode);

int __attribute__((weak)) sysmon_set_dcvs_v3_bus_perf_mode(
                                        HAP_power_request_t* request,
                                        HAP_dcvs_clk_perf_mode_t perf_mode);

int __attribute__((weak)) sysmon_set_dcvs_v3_protected_bus_corners(
                                        HAP_power_request_t* request,
                                        unsigned char enable_protected_corners);

int __attribute__((weak)) sysmon_set_ddr_perf_mode(
                                        HAP_power_request_t *request,
                                        unsigned int perf_mode);

int __attribute__((weak)) sysmon_dcvs_config(
                                        void *context,
                                        HAP_dcvs_request_t *request);

/**
 * @endcond
 */

/**
 * @defgroup helperapi Helper APIs for DCVS Duty Cycle
 * @{
 */

/**
 * Method to enable DCVS Duty Cycle.
 *
 * Calls HAP_power_set API with the provided context and selects
 * DCVS duty cycle mode via HAP_power_set_DCVS_v3 request type.
 *
 * @param[in] context User context - power client identifier to be used in
 *                                   HAP_power_set call.
 *
 * @param[in] max_active_time Max active time allowed per frame in ms
 *                            (optional, can pass 0 if don’t want to specify).
 *                            DCVS selects appropriate operating levels to
 *                            keep the activity time within the provided
 *                            maximum allowed time.
 *
 * @param[in] periodicity Frame time in ms (optional, can pass 0 if
 *                        don’t want to specify periodicity). For example,
 *                        periodicity = 100 (milli-seconds) for a
 *                        10 FPS activity. DCVS uses this as a hint while
 *                        predicting activity.
 *
 * @return
 * 0 upon success. \n
 * Nonzero upon failure. \n
 * AEE_EVERSIONNOTSUPPORT if unsupported.
 */
static inline int HAP_set_dcvs_v3_duty_cycle(
                                        void* context,
                                        uint32 max_active_time,
                                        uint32 periodicity)
{
    if (sysmon_set_dcvs_v3_duty_cycle)
        return sysmon_set_dcvs_v3_duty_cycle(
                                        context,
                                        max_active_time,
                                        periodicity);

    return AEE_EVERSIONNOTSUPPORT;
}

/**
 * Method to set duty cycle threshold params (periodicity and activity time hints)
 * in the request structure intended for HAP_power_set for request type set to
 * HAP_power_set_DCVS_v3.
 *
 * Sets the max_active_time and periodicity fields under dcvs_v3 payload of given
 * request structure.
 *
 * Note: Request type should be set to HAP_power_set_DCVS_v3.
 *
 * @param[in] request Pointer to request structure.
 *
 * @param[in] max_active_time Max active time allowed per frame in ms.
 *                            DCVS selects appropriate operating levels to
 *                            keep the activity time within the provided
 *                            maximum allowed time.
 *
 * @param[in] periodicity Frame time in ms (optional, can pass 0 if
 *                        don’t want to specify periodicity). For example,
 *                        periodicity = 100 (milli-seconds) for a
 *                        10 FPS activity. DCVS uses this as a hint while
 *                        predicting activity.
 *
 * @return
 * 0 upon success. \n
 * Nonzero upon failure. \n
 * AEE_EVERSIONNOTSUPPORT if unsupported.
 */
static inline int HAP_set_dcvs_v3_duty_cycle_params(
                                        HAP_power_request_t* request,
                                        uint32 max_active_time,
                                        uint32 periodicity)
{
    if (sysmon_set_dcvs_v3_duty_cycle_params)
    {
        return sysmon_set_dcvs_v3_duty_cycle_params(
                                            request,
                                            max_active_time,
                                            periodicity);
    }

    return AEE_EVERSIONNOTSUPPORT;
}

/**
 * @}
 */

/**
 * @defgroup enable_protected_corner_api Helper API for protected bus corners
 *
 * @{
 */
/**
 * On chipsets supporting bus corners above HAP_DCVS_VCORNER_TURBO_PLUS, to optimize residency at these corners,
 * target corner requests for bus are capped to HAP_DCVS_VCORNER_TURBO_PLUS by default.
 * Any request beyond HAP_DCVS_VCORNER_TURBO_PLUS (including HAP_DCVS_VCORNER_MAX) will be wrapped to HAP_DCVS_VCORNER_TURBO_PLUS.
 *
 * This API enables clients of HAP_power_set to override this protection when voting explicitly for bus corners
 * above HAP_DCVS_VCORNER_TURBO_PLUS in necessary use cases.
 *
 * Note:
 * API is supported starting with V79 QDSP6 architecture, AEE_EVERSIONNOTSUPPORT error (can be safely ignored) is returned by the API when not supported.
 *
 * Request type should be set to HAP_power_set_DCVS_v3.
 *
 * @param[in] request    Pointer to HAP_power_request_t structure with request type set to HAP_power_set_DCVS_v3.
 * @param[in] enable_protected_corners   1 - to consider bus corner requests above HAP_DCVS_VCORNER_TURBO_PLUS
 *                                       0 (default) - to cap bus corner requests to HAP_DCVS_VCORNER_TURBO_PLUS
 * @return
 *   0 upon success. \n
 *   Nonzero upon failure. \n
 *   AEE_EVERSIONNOTSUPPORT if unsupported.
 */

static inline int HAP_set_dcvs_v3_protected_bus_corners(
                                            HAP_power_request_t* request,
                                            unsigned char enable_protected_corners)
{
    if (sysmon_set_dcvs_v3_protected_bus_corners)
    {
        return sysmon_set_dcvs_v3_protected_bus_corners(request,
                                                enable_protected_corners);
    }

    return AEE_EVERSIONNOTSUPPORT;
}

/**
 * @}
 */
/**
 * @defgroup enable_ddr_perf_mode_api Helper API to enable DDR perf mode
 *
 * @{
 */
/**
 * This API enables clients of HAP_power_set to vote for DDR performance mode.
 *
 * Note:
 * API is supported starting with V79 QDSP6 architecture, AEE_EVERSIONNOTSUPPORT error (can be safely ignored) is returned by the API when not supported.
 *
 * Note: Request type should be set to HAP_power_set_DCVS_v3.
 *
 * @param[in] request Pointer to HAP_power_request_t structure with request type set to HAP_power_set_DCVS_v3
 *
 * @param[in] perf_mode  1 - to enable DDR performance mode
 *                       0 - to disable the DDR performance mode
 *
 * @return
 * 0 upon success. \n
 * Nonzero upon failure. \n
 * AEE_EVERSIONNOTSUPPORT if unsupported.
 */
static inline int HAP_set_ddr_perf_mode(
                                    HAP_power_request_t *request,
                                    unsigned int perf_mode)
{
    if (sysmon_set_ddr_perf_mode)
    {
        return sysmon_set_ddr_perf_mode(request, perf_mode);
    }

    return AEE_EVERSIONNOTSUPPORT;
}

/**
 * @}
 */

/**
 * @defgroup clk_perfmode_api APIs to specify core/bus clock frequency level within target voltage corner
 *
 * @{
 */

/**
 * Method to specify core clock frequency level corresponding to the
 * target corner request in the request structure intended for
 * HAP_power_set for request type set to HAP_power_set_DCVS_v3.
 *
 * By default, the highest core clock frequency available at the requested
 * target_corner is selected. Using this API, user can select either the
 * highest (HAP_DCVS_CLK_PERF_HIGH) or the lowest (HAP_DCVS_CLK_PERF_LOW)
 * core clock frequency at any given target_corner. If there is only one
 * core clock frequency available at the requested target_corner, both the
 * high and low settings will select the same.
 *
 * Note: Request type should be set to HAP_power_set_DCVS_v3.
 *
 * Supported on latest chipsets(released after Palima).
 *
 * @param[in] request Pointer to request structure.
 *
 * @param[in] perf_mode Perf mode to specify core clock frequency level
 *                      within target voltage corner.
 *
 * @return
 * 0 upon success. \n
 * Nonzero upon failure. \n
 * AEE_EVERSIONNOTSUPPORT if unsupported.
 */
static inline int HAP_set_dcvs_v3_core_perf_mode(
                                        HAP_power_request_t* request,
                                        HAP_dcvs_clk_perf_mode_t perf_mode)
{
    if (sysmon_set_dcvs_v3_core_perf_mode)
    {
        return sysmon_set_dcvs_v3_core_perf_mode(
                                            request,
                                            perf_mode);
    }

    return AEE_EVERSIONNOTSUPPORT;
}

/**
 * Method to specify bus clock frequency level corresponding to the
 * target corner request in the request structure intended for
 * HAP_power_set for request type set to HAP_power_set_DCVS_v3.
 *
 * By default, the highest bus clock frequency available at the requested
 * target_corner is selected. Using this API, user can select either the
 * highest (HAP_DCVS_CLK_PERF_HIGH) or the lowest (HAP_DCVS_CLK_PERF_LOW)
 * bus clock frequency at any given target_corner. If there is only one
 * bus clock frequency available at the requested target_corner, both the
 * high and low settings will select the same.
 *
 * Note: Request type should be set to HAP_power_set_DCVS_v3.
 *
 * Supported on latest chipsets(released after Palima).
 *
 * @param[in] request Pointer to request structure.
 *
 * @param[in] perf_mode Perf mode to specify bus clock frequency level
 *                      within target voltage corner.
 *
 * @return
 * 0 upon success. \n
 * Nonzero upon failure. \n
 * AEE_EVERSIONNOTSUPPORT if unsupported.
 */
static inline int HAP_set_dcvs_v3_bus_perf_mode(
                                        HAP_power_request_t* request,
                                        HAP_dcvs_clk_perf_mode_t perf_mode)
{
    if (sysmon_set_dcvs_v3_bus_perf_mode)
    {
        return sysmon_set_dcvs_v3_bus_perf_mode(
                                            request,
                                            perf_mode);
    }

    return AEE_EVERSIONNOTSUPPORT;
}

/**
 * @}
 */

/**
 * @defgroup HAP_dcvs_config HAP DCVS CONFIG API
 * @{
 */
/**
 * @brief Method to process the request based on the request type.
 *
 * Supported request types and their behavior:
 *
 * - `HAP_DCVS_CAPABILITY_QUERY`:
 *   To Query the supported DCVS capabilities.
 *   In this case, context should be NULL.
 *
 * - `HAP_DCVS_SET_EXP_VCORNERS`:
 *   To configure the expanded voltage corners core/bus/ceng.
 *   In this case, context should be HAP_power_request_t type
 *   and not NULL.
 *
 * - `HAP_DCVS_NOTIFY_REG`:
 *   Unsuppported request type.
 *
 * - `HAP_DCVS_NOTIFY_EVENT`:
 *   Unsuppported request type.
 *
 * - `HAP_DCVS_SET_BUS_SYS_STATE`:
 *   Unsuppported request type.
 *
 * - `HAP_DCVS_SET_HMX_PERF_MODE`:
 *   To set expanded perf mode value under hmx_v2 payload
 *   of the provided HAP_power_request_t request structure.
 *   Other parameters of the request structure are not modified.
 *   perf_mode parameter of hmx_v2 payload is ignored when using
 *   this option.
 *   In this case, context should be HAP_power_request_t type
 *   and not NULL.
 *
 * - `HAP_DCVS_SET_CENG_PERF_MODE`:
 *   To set expanded perf mode value under ceng_bus payload
 *   of the provided HAP_power_request_t request structure.
 *   Other parameters of the request structure are not modified.
 *   perf_mode parameter of ceng_bus payload is ignored when using
 *   this option.
 *   In this case, context should be HAP_power_request_t type
 *   and not NULL.
 *
 * @param[in] context  User context, which may be used depending on the request type.
 * @param[in] request  Pointer to a HAP_dcvs_request_t structure that specifies the DCVS operation.
 *
 * @return
 *  0 on success. \n
 *  Nonzero on failure. \n
 *  AEE_EVERSIONNOTSUPPORT if API is unsupported. \n
 *  AEE_ENULLCONTEXT if request is NULL. \n
 *  AEE_EBADPARM if parameters are invalid in request structure. \n
 *  AEE_ENOTYPE if type is not supported. \n
 */
static inline int HAP_dcvs_config(void *context,
                                  HAP_dcvs_request_t *request)
{
    if(sysmon_dcvs_config)
    {
        if (request == NULL)
        {
            return AEE_ENULLCONTEXT;
        }

        switch (request->type)
        {
            case HAP_DCVS_CAPABILITY_QUERY:
            {
                request->capability.version = HAP_DCVS_CAPABILITY_VERSION;
                request->capability.size = sizeof(HAP_dcvs_capability_t);
                break;
            }
            case HAP_DCVS_SET_EXP_VCORNERS:
            {
                request->exp_vcorners.version = HAP_DCVS_EXP_VCORNER_VERSION;
                request->exp_vcorners.size = sizeof(HAP_dcvs_exp_vcorner_t);
                break;
            }
            case HAP_DCVS_SET_HMX_PERF_MODE:
            {
                request->hmx_perf_mode.version = HAP_DCVS_HMX_EXP_PERF_MODE_VERSION;
                request->hmx_perf_mode.size = sizeof(HAP_dcvs_exp_perf_mode_t);
                break;
            }
            case HAP_DCVS_SET_CENG_PERF_MODE:
            {
                request->ceng_perf_mode.version = HAP_DCVS_CENG_EXP_PERF_MODE_VERSION;
                request->ceng_perf_mode.size = sizeof(HAP_dcvs_exp_perf_mode_t);
                break;
            }
            default:
            {
                return AEE_ENOTYPE;
            }
        }
        return sysmon_dcvs_config(context, request);
    }
    return AEE_EVERSIONNOTSUPPORT;
}

/**
 * @}
 */

#ifdef __cplusplus
}
#endif

#endif //HAP_DCVS_H_

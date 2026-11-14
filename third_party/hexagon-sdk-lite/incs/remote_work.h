
/*
  Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.
  All Rights Reserved.
  Confidential and Proprietary - Qualcomm Technologies, Inc.
*/
/**
 * @file remote_work.h
 * @brief Remote Work Control API
 *
 * This header defines the interface for submitting control requests
 * to the remote work scheduling subsystem.
 */
#ifndef REMOTE_WORK_H
#define REMOTE_WORK_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/** @defgroup remote_work_enums Remote Work Status API enums
 *  @{
 */
/* DSP work types supported by scheduler */
enum remote_work_type {
	/** FastRPC work type */
	REMOTE_WORK_TYPE_FASTRPC = 1,

	/** DSP queue work type */
	REMOTE_WORK_TYPE_DSPQUEUE,

	/** Always keep as last member */
	REMOTE_WORK_TYPE_MAX,
};

/* DSP work status type */
enum remote_work_status_type {
	/** Work item has started */
	REMOTE_WORK_STATUS_START = 1,

	/** Work item has completed */
	REMOTE_WORK_STATUS_END,

	/** Always keep as last member */
	REMOTE_WORK_STATUS_MAX,
};

/* DSP work status notification error codes */
enum remote_work_status_error {
	/**  Successfully notified */
	REMOTE_WORK_STATUS_SUCCESS = 0,

	/**  Invalid API call */
	REMOTE_WORK_STATUS_INVALID = 1,

	/**  Work aborted by user */
	REMOTE_WORK_STATUS_ABORTED = 2,

	/** Other Errors */
	REMOTE_WORK_STATUS_ERROR   = 3,
};

/**
 * Request IDs for remote work control interface
 */
enum remote_work_control_req_id {
	/**
	 * Notify the kernel scheduler about DSP work status changes.
	 *
	 * Use with remote_work_status_update to notify the scheduler when
	 * a DSP work (either FastRPC or DSPQueue) starts or ends, enabling
	 * priority-based scheduling decisions.
	 *
	 * This call blocks until the scheduler has processed the
	 * notification and it is the caller's turn to proceed.
	 * Any work submitted to the DSP before this call returns
	 * will be rejected by the scheduler.
	 *
	 * @data  remote_work_status_update *
	 */
	REMOTE_WORK_NOTIFY_STATUS,
};

/** @} // remote_work_enums
 */
/** @defgroup remote_work_status_update structure Remote Work Status API
 *  @{
 */
/**
 * Structure used for request ID
 * `REMOTE_WORK_NOTIFY_STATUS` in remote work control
 * interface.
 *
 * Describes the state of a DSP work item being
 * submitted to or completing on the DSP subsystem.
 * Populate all applicable fields before passing a
 * pointer to this structure as the data argument of
 * remote_work_control(). On return, inspect the error
 * field for the detailed outcome of the operation.
 */
typedef struct {
	/**
	 * [in] Type of work submitted to the DSP.
	 * Must be one of the values defined in enum remote_work_type.
	 * This field determines how the handle field is interpreted.
	 */
	enum remote_work_type type;

	/**
	 * [in] Lifecycle event associated with the work item.
	 * Set to REMOTE_WORK_STATUS_START when the work begins and
	 * REMOTE_WORK_STATUS_END when the work completes.
	 * Must be one of the values defined in enum remote_work_status_type.
	 */
	enum remote_work_status_type status;

	/**
	 * [in] Opaque handle identifying the DSP resource associated with
	 * this work item. The pointed-to value must remain valid for the
	 * duration of the remote_work_control() call.
	 * The caller must supply a pointer cast to the appropriate type
	 * based on the type field:
	 *   - REMOTE_WORK_TYPE_FASTRPC:  pointer to remote_handle64
	 *   - REMOTE_WORK_TYPE_DSPQUEUE: pointer to dspqueue_t
	 * Must not be NULL.
	 */
	void *handle;

	/**
	 * [in] Opaque string identifying a debug feature associated with
	 * this work item. Used for diagnostic and tracing purposes.
	 * May be NULL if no debug feature identifier is applicable, in
	 * which case debug_feature_id_len must be set to 0.
	 */
	char *debug_feature_id;

	/**
	 * [in] Length of the debug_feature_id string in bytes, not
	 * including the NULL terminator. Must be set to 0 if
	 * debug_feature_id is NULL.
	 */
	uint32_t debug_feature_id_len;

	/**
	 * [in] An identifier that indicates that this work is part of a
	 * larger effort which is comprised of multiple inferences. This
	 * value will be the same for all such inferences. May be NULL in
	 * the cases where there is no such relationship or if the
	 * application did not specify it. If non-NULL, group_id_len
	 * must be set to the length of the string excluding the NULL
	 * terminator.
	 */
	char *group_id;

	/**
	 * [in] Length of the group_id string in bytes, not including
	 * the NULL terminator. Must be set to 0 if group_id is NULL.
	 */
	uint32_t group_id_len;

	/**
	 * [in] Reserved for future use.
	 * Must be set to 0.
	 */
	uint32_t flags;

	/**
	 * [out] Detailed error code from enum remote_work_status_error
	 * indicating the specific outcome of the operation. This field
	 * is populated by remote_work_control() on return and must be
	 * checked when the function returns AEE_EFAILED to determine
	 * the root cause of the failure.
	 */
	int error;
} remote_work_status_update;


/** @} // remote_work_status_update
 */
/** @defgroup remote_work_control Remote Work Control API Functions
 *  @{
 */
/**
 * Send a control request to the remote work scheduling
 * interface.
 *
 * Dispatches the given request to the remote work
 * scheduler. The data parameter must point to the
 * structure corresponding to the supplied req ID and
 * datalen must equal the size of that structure.
 * On return, inspect the error field within the data
 * structure for the detailed outcome of the operation.
 *
 * @param [in] req     : Request ID from
 *                       enum remote_work_control_req_id
 *                       that identifies the operation.
 * @param [in] data    : Pointer to the request-specific
 *                       data structure. Must not be NULL.
 *                       Must remain valid for the
 *                       duration of the call.
 * @param [in] datalen : Size of the structure pointed
 *                       to by data, in bytes. Must equal
 *                       sizeof the corresponding
 *                       structure for the given req.
 *
 * @return AEE_SUCCESS on success.
 *         AEE_EFAILED on failure; inspect the error
 *         field of the data structure for the specific
 *         error code from enum remote_work_status_error.
 */
int remote_work_control(uint32_t req, void *data, uint32_t datalen);
/** @} //remote_work_control
 */
#ifdef __cplusplus
}
#endif
#endif /* REMOTE_WORK_H */

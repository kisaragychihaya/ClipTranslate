/*-----------------------------------------------------------------------------
 * Copyright (c) 2016-2020 Qualcomm Technologies, Inc.
 * All Rights Reserved.
 * Confidential and Proprietary - Qualcomm Technologies, Inc.
-----------------------------------------------------------------------------*/

#ifndef HAP_VTCM_MGR_H_
#define HAP_VTCM_MGR_H_

#ifdef __cplusplus
extern "C" {
#endif


int HAP_query_total_VTCM(unsigned int* page_size, unsigned int* page_count);

/**
 * API to query VTCM allocation status.
 *
 * @param[out] avail_block_size  Pointer to an @c unsigned @c int variable.
 *                               If this parameter is non-zero on success, the
 *                               memory location contains the maximum contiguous
 *                               memory chunk (in bytes) available in VTCM.
 * @param[out] max_page_size  Pointer to an @c unsigned @c int variable.
 *                            If this parameter is non-zero, the memory location
 *                            contains the maximum possible page size allocation
 *                            (in bytes) in the available portion of VTCM.
 * @param[out] num_pages  Pointer to an @c unsigned @c int variable.
 *                        If this parameter is non-zero on success, the memory
 *                        location contains the value of @p max_page_size.
 *
 * @return
 * @c int 0 on success. \n
 * Non-zero on failure.
 *
 * @par Example
 * @code
 *  unsigned int avail_block_size, max_page_size, num_pages;
 *  if (0 == HAP_query_avail_VTCM(&avail_block_size, &max_page_size, &num_pages))
 *  {
 *      // Query successful.
 *      // Use avail_block_size, max_page_size, num_pages
 *  }
 * @endcode
 */
int HAP_query_avail_VTCM(unsigned int* avail_block_size,
                          unsigned int* max_page_size,
                          unsigned int* num_pages);

/**
 * @}
 */


#ifdef __cplusplus
}
#endif

#endif //HAP_VTCM_MGR_H_

/**
 * Copyright (c) Qualcomm Technologies, Inc.
 * All Rights Reserved.
 * Confidential and Proprietary - Qualcomm Technologies, Inc.
 */

#ifndef HAP_CHIPINFO_H
#define HAP_CHIPINFO_H

/**
 *  @file
 *  @brief Header file for chipinfo HAP wrapper API's.
 */

#include <stdint.h>

/**
 * Structure to FeatureCode and P-Code information for the current device.
 * nFeatureCode SKU_IDs logical mapping.
 * nPcode logical mappings.
 * Combination of nPcode and nFeatureCode is used for identifying SKU.
 * This information is used internally to identify the hardware features
 * on the device.
 */
typedef struct
{
  uint32_t nFeatureCode;
  uint32_t nPcode;
} chip_sku_t;

/**
 * API returns the chip ID.
 *
 * @return
 * Chip ID associated with the part number read from the device.
 * UNKNOWN i.e '0' if no associated chip id could be found.
 * '0' if called before ChipInfo is initialized.
 */
uint32_t HAP_get_chip_id(void);

/*
 * Returns the chip family.
 * The chip family has no connection to the architectural family.
 * The chip family is used to group chips from specific industry segments
 * such as IOT, Auto, or Mobile.
 *
 * @return:  Chip Family if successful,
 *           UNKNOWN i.e '0' if no associated chip family can be found,
 *           or if called before ChipInfo is initialized
 */
uint32_t HAP_get_chip_family_id(void);

/**
 * Get SKU and Product Code information for the current device.
 *
 * This information can be used to make functional decisions, e.g.
 * selecting different DDR voltage plans.
 * If the SKU_ID and/or P_CODE fuses are not blown on this device, the
 * respective nFeatureCode and nPcode values of the returned struct
 * will be set to 0.
 *
 * @param[out] Pointer to chip_sku_t structure.
 *
 * @return
 * '0' upon success.
 * upon failure a valid chipinfo error code.
 */
 int HAP_get_chip_sku(chip_sku_t *hinfo);

 #endif // HAP_CHIPINFO_H
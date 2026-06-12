#ifndef __I2C_H
#define __I2C_H

int rtlsdr_check_dongle_model(void *dev, char *manufact_check, char *product_check);
/* ίδιο prototype με το rtl-sdr.h — ο NDK clang δεν δέχεται void* vs typed
 * (identical typedef redefinition = νόμιμο σε C11) */
typedef struct rtlsdr_dev rtlsdr_dev_t;
int rtlsdr_set_bias_tee_gpio(rtlsdr_dev_t *dev, int gpio, int on);
uint32_t rtlsdr_get_tuner_clock(void *dev);
int rtlsdr_i2c_write_fn(void *dev, uint8_t addr, uint8_t *buf, int len);
int rtlsdr_i2c_read_fn(void *dev, uint8_t addr, uint8_t *buf, int len);

#endif

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.dromara.hmily.demo.springcloud.account.service.impl;

import org.dromara.hmily.annotation.HmilyTCC;
import org.dromara.hmily.common.exception.HmilyRuntimeException;
import org.dromara.hmily.demo.springcloud.account.dto.AccountDTO;
import org.dromara.hmily.demo.springcloud.account.entity.AccountDO;
import org.dromara.hmily.demo.springcloud.account.mapper.AccountMapper;
import org.dromara.hmily.demo.springcloud.account.service.AccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The type Account service.
 *
 * @author xiaoyu
 */
@Service("accountService")
public class AccountServiceImpl implements AccountService {

    /**
     * logger.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(AccountServiceImpl.class);

    private final AccountMapper accountMapper;
    
    /**
     * Instantiates a new Account service.
     *
     * @param accountMapper the account mapper
     */
    @Autowired(required = false)
    public AccountServiceImpl(final AccountMapper accountMapper) {
        this.accountMapper = accountMapper;
    }

    @Override
    @HmilyTCC(confirmMethod = "confirm", cancelMethod = "cancel")
    public boolean payment(final AccountDTO accountDTO) {
        LOGGER.debug("============执行try付款接口===============");
        accountMapper.update(accountDTO);
        return Boolean.TRUE;
    }
    
    @Override
    public boolean testPayment(AccountDTO accountDTO) {
        accountMapper.testUpdate(accountDTO);
        return Boolean.TRUE;
    }
    
    @Override
    @HmilyTCC(confirmMethod = "confirm", cancelMethod = "cancel")
    public boolean mockWithTryException(AccountDTO accountDTO) {
        throw new HmilyRuntimeException("账户扣减异常！");
    }
    
    @Override
    @HmilyTCC(confirmMethod = "confirm", cancelMethod = "cancel")
    public boolean mockWithTryTimeout(AccountDTO accountDTO) {
        try {
            //模拟延迟 当前线程暂停10秒
            Thread.sleep(10000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        int decrease = accountMapper.update(accountDTO);;
        if (decrease != 1) {
            throw new HmilyRuntimeException("账户余额不足");
        }
        return true;
    }
    
    @Override
    public AccountDO findByUserId(final String userId) {
        return accountMapper.findByUserId(userId);
    }

    /**
     * Confirm boolean.
     *
     * @param accountDTO the account dto
     * @return the boolean
     */
    public boolean confirm(final AccountDTO accountDTO) {
        LOGGER.debug("============执行confirm 付款接口===============");
        final int rows = accountMapper.confirm(accountDTO);
        return Boolean.TRUE;
    }


    /**
     * Cancel boolean.
     *
     * @param accountDTO the account dto
     * @return the boolean
     */
    public boolean cancel(final AccountDTO accountDTO) {
        LOGGER.debug("============执行cancel 付款接口===============");
        final int rows = accountMapper.cancel(accountDTO);
        if (rows != 1) {
            throw new HmilyRuntimeException("取消扣减账户异常！");
        }
        return Boolean.TRUE;
    }
}

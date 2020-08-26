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

package org.dromara.hmily.repository.file;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import lombok.SneakyThrows;
import org.dromara.hmily.common.enums.HmilyActionEnum;
import org.dromara.hmily.common.exception.HmilyException;
import org.dromara.hmily.common.exception.HmilyRuntimeException;
import org.dromara.hmily.common.utils.LogUtil;
import org.dromara.hmily.config.api.ConfigEnv;
import org.dromara.hmily.config.api.entity.HmilyFileConfig;
import org.dromara.hmily.repository.spi.HmilyRepository;
import org.dromara.hmily.repository.spi.entity.HmilyParticipant;
import org.dromara.hmily.repository.spi.entity.HmilyParticipantUndo;
import org.dromara.hmily.repository.spi.entity.HmilyTransaction;
import org.dromara.hmily.repository.spi.exception.HmilyRepositoryException;
import org.dromara.hmily.serializer.spi.HmilySerializer;
import org.dromara.hmily.serializer.spi.exception.HmilySerializerException;
import org.dromara.hmily.spi.HmilySPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * file impl.
 *
 * @author xiaoyu
 * @author choviwu
 */
@SuppressWarnings("all")
@HmilySPI("file")
public class FileRepository implements HmilyRepository {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(FileRepository.class);
    
    private static final String HMILY_TRANSATION_FILE_DIRECTORY = "hmily";
    
    private static final String HMILY_TRANSATION_PARTICIPANT_FILE_DIRECTORY = "participant";
    
    private static final String HMILY_PARTICIPANT_UNDO = "undo";
    
    private static final ReentrantReadWriteLock LOCK = new ReentrantReadWriteLock();
    
    private static final int HMILY_READ_BYTE_SIZE = 2048;
    
    private static volatile boolean initialized;
    
    private HmilySerializer hmilySerializer;
    
    private String appName;
    
    private String filePath;
    
    @Override
    public void init(final String appName) {
        this.appName = appName;
        HmilyFileConfig fileConfig = ConfigEnv.getInstance().getConfig(HmilyFileConfig.class);
        fileConfig.setPath(fileConfig.getPath());
        filePath = fileConfig.getPath();
        makeDir();
    }
    
    @Override
    public void setSerializer(final HmilySerializer hmilySerializer) {
        this.hmilySerializer = hmilySerializer;
    }

    @SneakyThrows
    @Override
    public int createHmilyTransaction(final HmilyTransaction hmilyTransaction) throws HmilyRepositoryException {
        File file = new File(getTransationPath());
        try {
            final boolean exsist = isExsist(file, hmilyTransaction.getTransId());
            if (!exsist) {
                hmilyTransaction.setCreateTime(new Date());
                hmilyTransaction.setUpdateTime(new Date());
                createOrUpdateWriteFile(file, hmilyTransaction);
            } else {
                hmilyTransaction.setVersion(hmilyTransaction.getVersion() + 1);
                hmilyTransaction.setUpdateTime(new Date());
                createOrUpdateWriteFile(file, hmilyTransaction);
            }
            return HmilyRepository.ROWS;
        } catch (IOException e) {
            throw new HmilyException(e);
        }
    }

    @Override
    public int updateRetryByLock(final HmilyTransaction hmilyTransaction) {
        final int currentVersion = hmilyTransaction.getVersion();
        File file = new File(getTransationPath());
        try {
            final boolean exsist = isExsist(file, hmilyTransaction.getTransId());
            if (!exsist) {
                return HmilyRepository.FAIL_ROWS;
            }
            hmilyTransaction.setVersion(currentVersion + 1);
            hmilyTransaction.setRetry(hmilyTransaction.getRetry() + 1);
            hmilyTransaction.setUpdateTime(new Date());
            createOrUpdateWriteFile(file, hmilyTransaction);
            return HmilyRepository.ROWS;
        } catch (IOException e) {
            LogUtil.error(LOGGER, "updateRetryByLock occur a exception {}", () -> e);
        }
        return HmilyRepository.FAIL_ROWS;
    }

    @Override
    public HmilyTransaction findByTransId(final Long transId) {
        File file = new File(getTransationPath());
        boolean exsist = isExsist(file, transId);
        if (exsist) {
            return readFile(file, HmilyTransaction.class, transId);
        }
        return null;
    }
    
    @Override
    public List<HmilyTransaction> listLimitByDelay(final Date date, final int limit) {
        File file = new File(getTransationPath());
        return listByFilter(file, HmilyTransaction.class, (hmilyTransaction, params) -> {
            Date dateParam = (Date) params[0];
            int limitParam = (int) params[1];
            boolean filterResult = dateParam.before(hmilyTransaction.getUpdateTime())
                    && Objects.equals(appName, hmilyTransaction.getAppName())
                    && limitParam-- > 0;
            // write back to params
            params[1] = limitParam;
            return filterResult;
        }, date, limit);
    }
    
    @Override
    public int updateHmilyTransactionStatus(final Long transId, final Integer status) throws HmilyRepositoryException {
        File file = new File(getTransationPath());
        try {
            boolean exsist = isExsist(file, transId);
            if (!exsist) {
                return HmilyRepository.FAIL_ROWS;
            }
            HmilyTransaction hmilyTransaction = readFile(file, HmilyTransaction.class, transId);
            if (hmilyTransaction == null) {
                return HmilyRepository.FAIL_ROWS;
            }
            hmilyTransaction.setStatus(status);
            hmilyTransaction.setVersion(hmilyTransaction.getVersion() + 1);
            hmilyTransaction.setUpdateTime(new Date());
            createOrUpdateWriteFile(file, hmilyTransaction);
            return HmilyRepository.ROWS;
        } catch (IOException e) {
            LogUtil.error(LOGGER, "updateHmilyTransactionStatus occur a exception {}", () -> e);
        }
        return HmilyRepository.FAIL_ROWS;
    }
    
    @Override
    public int removeHmilyTransaction(final Long transId) {
        File file = new File(getTransationPath());
        try {
            boolean exsist = isExsist(file, transId);
            if (!exsist) {
                return HmilyRepository.FAIL_ROWS;
            }
            HmilyTransaction hmilyTransaction = readFile(file, HmilyTransaction.class, transId);
            if (hmilyTransaction == null) {
                return HmilyRepository.FAIL_ROWS;
            }
            boolean delete = deleteFile(file, transId);
            return delete ? HmilyRepository.ROWS : HmilyRepository.FAIL_ROWS;
        } catch (IOException e) {
            LogUtil.error(LOGGER, "updateHmilyTransactionStatus occur a exception {}", () -> e);
        }
        return HmilyRepository.FAIL_ROWS;
    }

    @Override
    public int removeHmilyTransactionByData(final Date date) {
        File file = new File(getTransationPath());
        return removeByFilter(file, HmilyTransaction.class, (hmilyTransaction, params) -> {
            Date dateParam = (Date) params[0];
            return dateParam.before(hmilyTransaction.getUpdateTime()) && hmilyTransaction.getStatus() == HmilyActionEnum.DELETE.getCode();
        }, date);
    }

    @Override
    public int createHmilyParticipant(final HmilyParticipant hmilyParticipant) throws HmilyRepositoryException {
        File file = new File(getTransationParticipantPath());
        try {
            boolean exsist = isExsist(file, hmilyParticipant.getTransId());
            if (!exsist) {
                hmilyParticipant.setCreateTime(new Date());
                hmilyParticipant.setUpdateTime(new Date());
                createOrUpdateParticipantWriteFile(file, hmilyParticipant);
            } else {
                hmilyParticipant.setVersion(hmilyParticipant.getVersion() + 1);
                hmilyParticipant.setUpdateTime(new Date());
                createOrUpdateParticipantWriteFile(file, hmilyParticipant);
            }
            return HmilyRepository.ROWS;
        } catch (IOException e) {
            throw new HmilyException(e);
        }
    }

    @Override
    public List<HmilyParticipant> findHmilyParticipant(final Long participantId) {
        File file = new File(getTransationParticipantPath());
        return listByFilter(file, HmilyParticipant.class, (hmilyParticipant, params) -> {
            Long participantIdParam = (Long) params[0];
            return participantIdParam.compareTo(hmilyParticipant.getParticipantId()) == 0
                    || (hmilyParticipant.getParticipantRefId() != null && participantIdParam.compareTo(hmilyParticipant.getParticipantRefId()) == 0);
        }, participantId);
    }
    
    @Override
    public List<HmilyParticipant> listHmilyParticipant(final Date date, final String transType, final int limit) {
        File file = new File(getTransationParticipantPath());
        return listByFilter(file, HmilyParticipant.class, (hmilyParticipant, params) -> {
            Date dateParam = (Date) params[0];
            String transTypeParam = (String) params[1];
            int limitParam = (int) params[2];
            boolean filterResult = dateParam.before(hmilyParticipant.getUpdateTime())
                    && Objects.equals(appName, hmilyParticipant.getAppName())
                    && Objects.equals(transTypeParam, hmilyParticipant.getTransType())
                    && (hmilyParticipant.getStatus().compareTo(HmilyActionEnum.DELETE.getCode()) != 0
                    && hmilyParticipant.getStatus().compareTo(HmilyActionEnum.DEATH.getCode()) != 0)
                    && limitParam-- > 0;
            params[2] = limitParam;
            return filterResult;
        }, date, transType, limit);
    }

    @Override
    public List<HmilyParticipant> listHmilyParticipantByTransId(final Long transId) {
        File file = new File(getTransationParticipantPath());
        return listByFilter(file, HmilyParticipant.class, (hmilyParticipant, params) -> transId.compareTo(hmilyParticipant.getTransId()) == 0, transId);
    }

    @Override
    public boolean existHmilyParticipantByTransId(final Long transId) {
        return existByFilter((hmilyParticipant, params) -> {
            Long transIdParam = (Long) params[0];
            return transIdParam.compareTo(hmilyParticipant.getTransId()) == 0;
        }, transId);
    }
    
    @Override
    public int updateHmilyParticipantStatus(final Long participantId, final Integer status) throws HmilyRepositoryException {
        File file = new File(getTransationParticipantPath());
        try {
            boolean exsist = isExsist(file, participantId);
            if (!exsist) {
                return HmilyRepository.FAIL_ROWS;
            }
            HmilyParticipant hmilyParticipant = readFile(file, HmilyParticipant.class, participantId);
            if (hmilyParticipant == null) {
                return HmilyRepository.FAIL_ROWS;
            }
            hmilyParticipant.setStatus(status);
            hmilyParticipant.setVersion(hmilyParticipant.getVersion() + 1);
            hmilyParticipant.setUpdateTime(new Date());
            createOrUpdateParticipantWriteFile(file, hmilyParticipant);
            return HmilyRepository.ROWS;
        } catch (IOException e) {
            LogUtil.error(LOGGER, "updateHmilyParticipantStatus occur a exception {}", () -> e);
        }
        return HmilyRepository.FAIL_ROWS;
    }

    @Override
    public int removeHmilyParticipant(final Long participantId) {
        File file = new File(getTransationParticipantPath());
        try {
            boolean exsist = isExsist(file, participantId);
            if (!exsist) {
                return HmilyRepository.FAIL_ROWS;
            }
            HmilyParticipant hmilyParticipant = readFile(file, HmilyParticipant.class, participantId);
            if (hmilyParticipant == null) {
                return HmilyRepository.FAIL_ROWS;
            }
            boolean delete = deleteFile(file, participantId);
            return delete ? HmilyRepository.ROWS : HmilyRepository.FAIL_ROWS;
        } catch (IOException e) {
            LogUtil.error(LOGGER, "updateHmilyTransactionStatus occur a exception {}", () -> e);
        }
        return HmilyRepository.FAIL_ROWS;
    }

    @Override
    public int removeHmilyParticipantByData(final Date date) {
        File file = new File(getTransationParticipantPath());
        return removeByFilter(file, HmilyParticipant.class, (hmilyParticipant, params) -> {
            Date dateParam = (Date) params[0];
            return dateParam.before(hmilyParticipant.getUpdateTime()) && hmilyParticipant.getStatus() == 4;
        }, date);
    }

    @Override
    public boolean lockHmilyParticipant(final HmilyParticipant hmilyParticipant) {
        final int currentVersion = hmilyParticipant.getVersion();
        File file = new File(getTransationParticipantPath());
        try {
            boolean exsist = isExsist(file, hmilyParticipant.getParticipantId());
            if (!exsist) {
                LogUtil.warn(LOGGER, "path {} is not exists.", () -> file.getPath());
                return false;
            }
            hmilyParticipant.setVersion(currentVersion + 1);
            hmilyParticipant.setRetry(hmilyParticipant.getRetry() + 1);
            hmilyParticipant.setUpdateTime(new Date());
            createOrUpdateParticipantWriteFile(file, hmilyParticipant);
            return true;
        } catch (IOException e) {
            LogUtil.error(LOGGER, "updateRetryByLock occur a exception {}", () -> e);
        }
        return false;
    }

    @Override
    public int createHmilyParticipantUndo(final HmilyParticipantUndo hmilyParticipantUndo) {
        File file = new File(getParticipantUndoPath());
        try {
            boolean exsist = isExsist(file, hmilyParticipantUndo.getUndoId());
            if (!exsist) {
                hmilyParticipantUndo.setCreateTime(new Date());
                hmilyParticipantUndo.setUpdateTime(new Date());
                createOrUpdateParticipantUndoWriteFile(file, hmilyParticipantUndo);
            } else {
                hmilyParticipantUndo.setUpdateTime(new Date());

                createOrUpdateParticipantUndoWriteFile(file, hmilyParticipantUndo);
            }
            return HmilyRepository.ROWS;
        } catch (IOException e) {
            throw new HmilyException(e);
        }
    }

    @Override
    public List<HmilyParticipantUndo> findHmilyParticipantUndoByParticipantId(final Long participantId) {
        File file = new File(getParticipantUndoPath());
        return listByFilter(file, HmilyParticipantUndo.class, (undo, params) -> {
            Long participantIdParam = (Long) params[0];
            return participantIdParam.compareTo(undo.getParticipantId()) == 0;
        }, participantId);
    }

    @Override
    public int removeHmilyParticipantUndo(final Long undoId) {
        File file = new File(getParticipantUndoPath());
        try {
            boolean exsist = isExsist(file, undoId);
            if (!exsist) {
                return HmilyRepository.FAIL_ROWS;
            }
            HmilyParticipantUndo hmilyParticipantUndo = readFile(file, HmilyParticipantUndo.class, undoId);

            if (hmilyParticipantUndo == null) {
                return HmilyRepository.FAIL_ROWS;
            }
            boolean delete = deleteFile(file, undoId);
            return delete ? HmilyRepository.ROWS : HmilyRepository.FAIL_ROWS;
        } catch (IOException e) {
            LogUtil.error(LOGGER, "updateHmilyTransactionStatus occur a exception {}", () -> e);
        }
        return HmilyRepository.FAIL_ROWS;
    }

    @Override
    public int removeHmilyParticipantUndoByData(final Date date) {
        File file = new File(getParticipantUndoPath());
        return removeByFilter(file, HmilyParticipantUndo.class, (undo, params) -> {
            Date dateParam = (Date) params[0];
            return dateParam.before(undo.getUpdateTime()) && undo.getStatus().compareTo(4) == 0;
        }, date);
    }
    
    @Override
    public int updateHmilyParticipantUndoStatus(final Long undoId, final Integer status) {
        File file = new File(getParticipantUndoPath());
        try {
            boolean exsist = isExsist(file, undoId);
            if (!exsist) {
                return HmilyRepository.FAIL_ROWS;
            }
            HmilyParticipantUndo hmilyParticipantUndo = readFile(file, HmilyParticipantUndo.class, undoId);
            hmilyParticipantUndo.setStatus(status);
            hmilyParticipantUndo.setUpdateTime(new Date());
            createOrUpdateParticipantUndoWriteFile(file, hmilyParticipantUndo);
            return HmilyRepository.ROWS;
        } catch (IOException e) {
            LogUtil.error(LOGGER, "updateHmilyParticipantStatus occur a exception {}", () -> e);
        }
        return HmilyRepository.FAIL_ROWS;
    }
    
    private String getTransationPath() {
        return filePath + File.separator + HMILY_TRANSATION_FILE_DIRECTORY;
    }
    
    private String getTransationParticipantPath() {
        return getTransationPath() + File.separator + appName;
    }
    
    private String getParticipantUndoPath() {
        return getTransationParticipantPath() + File.separator + HMILY_PARTICIPANT_UNDO;
    }
    
    private void makeDir() {
        if (!initialized) {
            synchronized (FileRepository.class) {
                if (!initialized) {
                    File rootPathFile = new File(filePath);
                    if (!rootPathFile.exists()) {
                        boolean result = rootPathFile.mkdir();
                        if (!result) {
                            throw new HmilyRuntimeException("cannot create root path, the path to create is:" + filePath);
                        }
                        initDir();
                        initialized = true;
                    } else if (!rootPathFile.isDirectory()) {
                        throw new HmilyRuntimeException("rootPath is not directory");
                    } else {
                        initDir();
                    }
                }
            }
        }
    }
    
    private void initDir() {
        File transationFileDir = new File(getTransationPath());
        if (!transationFileDir.exists()) {
            transationFileDir.getParentFile().mkdirs();
            boolean mkdirs = transationFileDir.mkdirs();
            if (!mkdirs) {
                throw new HmilyRuntimeException("cannot create transationFile path, the path to create is:" + transationFileDir.getAbsolutePath());
            }
        }
        File participantFileDir = new File(getTransationParticipantPath());
        if (!participantFileDir.exists()) {
            participantFileDir.getParentFile().mkdirs();
            boolean mkdirs = participantFileDir.mkdirs();
            if (!mkdirs) {
                throw new HmilyRuntimeException("cannot create participantFile path, the path to create is:" + participantFileDir.getAbsolutePath());
            }
        }
        File participantUndoFileDir = new File(getParticipantUndoPath());
        if (!participantUndoFileDir.exists()) {
            participantUndoFileDir.getParentFile().mkdirs();
            boolean mkdirs = participantUndoFileDir.mkdirs();
            if (!mkdirs) {
                throw new HmilyRuntimeException("cannot create participantUndoFile path, the path to create is:" + participantUndoFileDir.getAbsolutePath());
            }
        }
    }
    
    private boolean isExsist(final File file, final Long transId) {
        boolean exsist = Arrays.asList(file.listFiles())
                .stream().filter(c -> Objects.equals(String.valueOf(transId), c.getName()))
                .findFirst().isPresent();
        return exsist;
    }
    
    private void createOrUpdateWriteFile(final File file, final HmilyTransaction hmilyTransaction) throws IOException {
        FileOutputStream fos = null;
        LOCK.writeLock().lock();
        try {
            File curFile = new File(concatPath(file.getAbsolutePath(), hmilyTransaction.getTransId()));
            if (!curFile.exists()) {
                boolean newFile = curFile.createNewFile();
            }
            fos = new FileOutputStream(curFile);
            fos.write(hmilySerializer.serialize(hmilyTransaction));
        } finally {
            if (fos != null) {
                fos.close();
            }
            LOCK.writeLock().unlock();
        }
    }
    
    private void createOrUpdateParticipantWriteFile(final File file, final HmilyParticipant hmilyParticipant) throws IOException {
        FileOutputStream fos = null;
        LOCK.writeLock().lock();
        try {
            File curFile = new File(concatPath(file.getAbsolutePath(), hmilyParticipant.getParticipantId()));
            if (!curFile.exists()) {
                boolean newFile = curFile.createNewFile();
            }
            fos = new FileOutputStream(curFile);
            fos.write(hmilySerializer.serialize(hmilyParticipant));
        } finally {
            if (fos != null) {
                fos.close();
            }
            LOCK.writeLock().unlock();
        }
    }
    
    private void createOrUpdateParticipantUndoWriteFile(final File file, final HmilyParticipantUndo hmilyParticipantUndo) throws IOException {
        FileOutputStream fos = null;
        LOCK.writeLock().lock();
        try {
            File curFile = new File(concatPath(file.getAbsolutePath(), hmilyParticipantUndo.getUndoId()));
            if (!curFile.exists()) {
                boolean newFile = curFile.createNewFile();
            }
            fos = new FileOutputStream(curFile);
            fos.write(hmilySerializer.serialize(hmilyParticipantUndo));
        } finally {
            if (fos != null) {
                fos.close();
            }
            LOCK.writeLock().unlock();
        }
    }
    
    private boolean deleteFile(final File file, final Long transId) throws IOException {
        File curFile = new File(concatPath(file.getAbsolutePath(), transId));
        if (!curFile.exists()) {
            return true;
        }
        return curFile.delete();
    }
    
    private String concatPath(final String filePath, final Long id) {
        return filePath + File.separator + id;
    }
    
    @SneakyThrows
    private <T> T readFile(final File file, final Class<T> clazz, final Long transId) {
        LOCK.readLock().lock();
        FileInputStream fis = null;
        try {
            File curFile = new File(concatPath(file.getAbsolutePath(), transId));
            if (!curFile.exists()) {
                return null;
            }
            fis = new FileInputStream(curFile);
            byte[] bytes = new byte[HMILY_READ_BYTE_SIZE];
            fis.read(bytes);
            return hmilySerializer.deSerialize(bytes, clazz);
        } catch (IOException | HmilySerializerException e) {
            LogUtil.error(LOGGER, " read file exception ,because is {}", () -> e);
            return null;
        } finally {
            if (fis != null) {
                fis.close();
            }
            LOCK.readLock().unlock();
        }
    }
    
    private <T> List<T> listByFilter(final File file, final Class<T> deserializeClass, final Filter<T> filter, final Object... params) {
        try {
            File[] files = file.listFiles();
            if (Objects.isNull(files) || files.length <= 0) {
                return Collections.emptyList();
            }
            List<T> result = new ArrayList<>();
            for (File child : files) {
                if (child.isFile()) {
                    T t = readFile(file, deserializeClass, Long.valueOf(child.getName()));
                    if (t == null) {
                        continue;
                    }
                    if (filter.filter(t, params)) {
                        result.add(t);
                    }
                }
            }
            return result;
        } catch (Exception e) {
            LogUtil.error(LOGGER, "listByFilter occur a exception {}", () -> e);
        }
        return Collections.emptyList();
    }

    private <T> boolean existByFilter(final Filter<HmilyParticipant> filter, final Object... params) {
        File file = new File(getTransationParticipantPath());
        File[] files = file.listFiles();
        if (Objects.isNull(files) || files.length <= 0) {
            return false;
        }
        for (File child : files) {
            if (child.isFile()) {
                HmilyParticipant hmilyParticipant = readFile(file, HmilyParticipant.class, Long.valueOf(child.getName()));
                if (hmilyParticipant == null) {
                    continue;
                }
                if (filter.filter(hmilyParticipant, params)) {
                    return true;
                }
            }
        }
        return false;
    }

    private <T> int removeByFilter(final File file, final Class<T> deserializeClass, final Filter<T> filter, final Object... params) {
        File[] files = file.listFiles();
        if (Objects.isNull(files) || files.length <= 0) {
            return HmilyRepository.FAIL_ROWS;
        }
        int count = 0;
        for (File childFiles : files) {
            if (childFiles.isFile()) {
                T t = readFile(file, deserializeClass, Long.parseLong(childFiles.getName()));
                if (t == null) {
                    continue;
                }
                if (filter.filter(t, params)) {
                    childFiles.delete();
                    count++;
                }
            }
        }
        return count;
    }
    
    /**
     * The interface Filter.
     *
     * @param <T> the type parameter
     */
    interface Filter<T> {
        
        /**
         * Filter boolean.
         *
         * @param t      the t
         * @param params the params
         * @return the boolean
         */
        boolean filter(T t, Object... params);
    }
}

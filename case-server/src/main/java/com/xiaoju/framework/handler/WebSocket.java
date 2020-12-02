package com.xiaoju.framework.handler;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.xiaoju.framework.constants.SystemConstant;
import com.xiaoju.framework.constants.enums.StatusCode;
import com.xiaoju.framework.entity.dto.RecordWsDto;
import com.xiaoju.framework.entity.exception.CaseServerException;
import com.xiaoju.framework.entity.persistent.ExecRecord;
import com.xiaoju.framework.entity.persistent.TestCase;
import com.xiaoju.framework.entity.xmind.IntCount;
import com.xiaoju.framework.mapper.TestCaseMapper;
import com.xiaoju.framework.service.CaseService;
import com.xiaoju.framework.service.RecordService;
import com.xiaoju.framework.util.TreeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import javax.websocket.*;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * 协同类
 *
 * @author didi
 * @date 2020/9/23
 */
@Component
@ServerEndpoint(value = "/api/case/{caseId}/{recordId}/{isCore}/{user}")
public class WebSocket {

    /**
     * 常量
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(WebSocket.class);

    private static final String PING_MESSAGE = "ping ping ping";

    private static final String UNDEFINED = "undefined";

    /**
     * 依赖
     */
    public static CaseService caseService;
    public static RecordService recordService;
    public static TestCaseMapper caseMapper;

    /**
     * 在Websocket.class粒度下，存储所有的websocket信息
     */
    public static ConcurrentHashMap<String, WebSocket> webSocket = new ConcurrentHashMap<>();

    /**
     * 在Websocket.class粒度下，存储所有的websocket.key,主要用户方便获取用户信息
     * caseId,recordId,sessionId
     * caseId和recordId都在onOpen时fill了，而sessionId需要在使用时fill
     */
    public static List<String> keys = new CopyOnWriteArrayList<>();

    /**
     * 单机模式下可以使用公平锁, 对数据的访问和获取都做一次顺序拦截
     */
    private static ReentrantLock lock = new ReentrantLock(true);

    /**
     * 每个websocket所持有的基本信息
     */
    private String caseId;
    private Session session;
    private String caseContent;
    private long updateCaseTime;
    private long updateRecordTime;
    private String recordId;
    private String isCore;
    private String user;
    private long pongTimeStamp;

    @Override
    public String toString() {
        return String.format("[Websocket Info][%s]caseId=%s, sessionId=%s, recordId=%s, isCoreCase=%s",
                recordId == null || UNDEFINED.equals(recordId) ? "测试用例" : "执行任务", caseId, session.getId(), recordId, isCore
        );
    }

    public String sessionKey() {
        return this.caseId + this.recordId + fill(session.getId());
    }


    static {
        // 线程池，每过5s向所有session发送ping，如果6s内没有收到响应，会执行session.close()去关闭session
        LOGGER.info("[线程池执行ping-pong] time = {}", System.currentTimeMillis());
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 2, 3,
                TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(3),
                new ThreadPoolExecutor.DiscardOldestPolicy());
        executor.execute(() -> {
            try {
                // 看看是不是链接关闭了，如果没有收到就关闭
                while (true) {
                    for (Map.Entry<String, WebSocket> entry : webSocket.entrySet()) {
                        if (!UNDEFINED.equals(entry.getValue().caseId)) {
                            // 其实这里可以把方法变成static
                            entry.getValue().sendMessage(entry.getValue().session, PING_MESSAGE);
                            // 看看是不是过时的内容
                            if (System.currentTimeMillis() - entry.getValue().pongTimeStamp > 6000) {
                                LOGGER.error("[线程池执行ping-pong出错]准备关闭当前websocket={}", entry.getValue().toString());
                                entry.getValue().onClose();
                            }
                        }
                    }
                    Thread.sleep(5000);
                }
            } catch (Exception e) {
                LOGGER.error("[线程池执行ping-pong出错]错误原因e={}", e.getMessage());
                e.printStackTrace();
            }
        });
    }

    @OnOpen
    public synchronized void onOpen(@PathParam(value = "caseId") String caseId,
                                    @PathParam(value = "recordId") String recordId,
                                    @PathParam(value = "isCore") String isCore,
                                    @PathParam(value = "user") String user,
                                    Session session) throws IOException {
        LOGGER.info("[websocket-onOpen 开启新的session][{}]", toString());
        this.session = session;
        this.caseId = fill(caseId);
        this.recordId = fill(recordId);
        this.isCore = isCore;
        this.user = user;
        this.updateCaseTime = 0;
        this.updateRecordTime = 0;
        this.pongTimeStamp = System.currentTimeMillis();
        if (UNDEFINED.equals(this.caseId)) {
            throw new CaseServerException("任务id为空", StatusCode.WS_UNKNOWN_ERROR);
        }

        lock.lock();
        try {
            keys.add(this.caseId + this.recordId + fill(session.getId()));
            webSocket.put(this.caseId + this.recordId + fill(session.getId()), this);
        } finally {
            lock.unlock();
        }

        /* 打开当前主用例的用户数大于1后，会先更新case */
        if (getKeysByCaseId(this.caseId, this.recordId).size() > 1) {
            save(this.caseId, this.recordId, user);
        }

        open(this.caseId, this.recordId, isCore);
    }

    @OnClose
    public synchronized void onClose() {
        LOGGER.info("[websocket-onClose 关闭当前session成功]当前session={}", sessionKey());
        if (UNDEFINED.equals(this.caseId)) {
            throw new CaseServerException("任务id为空", StatusCode.WS_UNKNOWN_ERROR);
        }

        save(this.caseId, this.recordId, this.user);
        lock.lock();
        try {
            webSocket.remove(this.caseId + this.recordId + fill(session.getId()), this);
            WebSocket.keys.remove(this.caseId + this.recordId + fill(session.getId()));
        } finally {
            lock.unlock();
        }
    }

    @OnMessage(maxMessageSize = 1048576)
    public synchronized void onMessage(String message, Session session) throws IOException {
        if (message.contains("pongpongpong")) {
            pongTimeStamp = System.currentTimeMillis();
            return;
        }

        if (null == webSocket.get(this.caseId + this.recordId + fill(session.getId()))) {
            sendMessage(session, StatusCode.WS_UNKNOWN_ERROR.getCode());
            return;
        }

        JSONObject request = JSON.parseObject(message);
        if (StringUtils.isEmpty(request.getString("patch"))) {
            // 没有patch就不要触发保存
            return;
        }
        JSONArray patch = (JSONArray) request.get("patch");
        long currentVersion = ((JSONObject) request.get("case")).getLong("base");
        String msg2Other = patch.toJSONString().replace("[[{", "[[{\"op\":\"replace\",\"path\":\"/base\",\"value\":" + (currentVersion + 1) + "},{");
        String msg2Own = "[[{\"op\":\"replace\",\"path\":\"/base\",\"value\":" + (currentVersion + 1) + "}]]";
        // 发送给别人 也发送给自己
        sendMessage(getKeysByCaseId(this.caseId, this.recordId, fill(session.getId())), msg2Other);
        sendMessage(session, msg2Own);
        this.caseContent = ((JSONObject) request.get("case")).toJSONString().replace("\"base\":" + currentVersion, "\"base\":" + (currentVersion + 1));
        if (patch.toJSONString().contains("/progress")) {
            // 如果是任务的修改，那么更新任务时间
            this.updateRecordTime = System.currentTimeMillis();
        } else {
            // 用例修改更新用例时间
            this.updateCaseTime = System.currentTimeMillis();
        }
    }

    @OnError
    public synchronized void onError(Session session, Throwable e) throws IOException {
        LOGGER.info("[websocket-onError 会话出现异常]当前session={}, 原因={}", sessionKey(), e.getMessage());
        e.printStackTrace();

        // 给一个机会去触发当前内容的保存
        save(this.caseId, this.recordId, this.user);
        lock.lock();
        try {
            webSocket.remove(this.caseId + this.recordId + fill(session.getId()), this);
            WebSocket.keys.remove(this.caseId + this.recordId + fill(session.getId()));
        } finally {
            lock.unlock();
        }
        sendMessage(session, StatusCode.WS_UNKNOWN_ERROR.getCode());
    }

    /**
     * 给指定session发送消息
     */
    private void sendMessage(Session s, String message) throws IOException {
        if (s != null && s.isOpen()) {
            s.getBasicRemote().sendText(message);
        }
    }

    /**
     * 批量发送消息
     */
    private void sendMessage(List<String> keys, String message) throws IOException {
        for (String key : keys) {
            sendMessage(webSocket.get(key).session, message);
        }
    }

    /**
     * 获取当前case/record下的所有用户
     */
    private List<String> getKeysByCaseId(String caseId, String recordId) {
        return keys.stream().filter(key -> key.startsWith(caseId + recordId)).collect(Collectors.toList());
    }

    /**
     * 获取当前case/record下的其他用户
     */
    private List<String> getKeysByCaseId(String caseId, String recordId, String sessionId) {
        List<String> keysRet = getKeysByCaseId(caseId, recordId);
        return keysRet.stream().filter(key -> !key.equals(caseId+recordId+sessionId)).collect(Collectors.toList());
    }

    /**
     * 根据recordId是否为undefined判断为更新任务还是用例
     * @see #onOpen(String, String, String, String, Session)
     * @see #onClose()
     * @see #onError(Session, Throwable)
     */
    private void save(String caseId, String recordId, String user) {
        if (!UNDEFINED.equals(recordId)) {
            saveRecord(caseId, recordId, user);
        } else {
            saveCase(caseId);
        }
    }

    /**
     * 保存任务
     * @see #save(String, String, String)
     */
    private void saveRecord(String caseId, String recordId, String user) {
        List<String> keys = getKeysByCaseId(caseId, recordId);
        long maxTime = 0;
        String keySave = "";
        JSONObject jsonObject = new JSONObject();
        JSONObject jsonProgress = new JSONObject();
        int totalCount = 0;
        int passCount = 0;
        int successCount = 0;
        int failCount = 0;
        int blockCount = 0;
        int ignoreCount = 0;
        // 将用例内容更新为最新
        for (String key : keys) {
            if (webSocket.get(key).updateRecordTime > maxTime) {
                maxTime = webSocket.get(key).updateRecordTime;
                jsonObject = TreeUtil.parse(webSocket.get(key).caseContent);
                jsonProgress = jsonObject.getJSONObject("progress");
                totalCount = jsonObject.getInteger("totalCount");
                passCount = jsonObject.getInteger("passCount");
                failCount = jsonObject.getInteger("failCount");
                blockCount = jsonObject.getInteger("blockCount");
                successCount = jsonObject.getInteger("successCount");
                ignoreCount = jsonObject.getInteger("ignoreCount");
                keySave = key;
            }
        }

        if (StringUtils.isEmpty(keySave)) {
            return;
        }

        //获取数据库更新时间
        RecordWsDto dto = recordService.getWsRecord(Long.parseLong(recordId));
        long recordUpdateTime = dto.getUpdateTime().getTime();
        long wsUpdateTime = webSocket.get(keySave).updateRecordTime;
        if (recordUpdateTime < wsUpdateTime) {
            if (!jsonObject.containsKey("progress")) {
                LOGGER.info("current no record to save.");
                return;
            }
            for (String key : keys) {
                webSocket.get(key).caseContent = webSocket.get(keySave).caseContent;
                webSocket.get(key).updateRecordTime = 0L;
            }

            StringBuilder executors;
            if (StringUtils.isEmpty(dto.getExecutors())) {
                executors = new StringBuilder(user);
            } else {
                String executor = dto.getExecutors();
                executors = new StringBuilder(executor);
                String[] list = executor.split(SystemConstant.COMMA);
                if (!Arrays.asList(list).contains(user)) {
                    //无重复则添加，又重复不添加
                    if (list.length == 0) {
                        executors.append(user);
                    } else {
                        executors.append(SystemConstant.COMMA).append(user);
                    }
                }
            }
            ExecRecord recordUpdate = new ExecRecord();
            recordUpdate.setId(Long.valueOf(recordId));
            recordUpdate.setExecutors(executors.toString());
            recordUpdate.setModifier(user);
            recordUpdate.setGmtModified(new Date(System.currentTimeMillis()));
            recordUpdate.setCaseContent(jsonProgress.toJSONString());
            recordUpdate.setFailCount(failCount);
            recordUpdate.setBlockCount(blockCount);
            recordUpdate.setIgnoreCount(ignoreCount);
            recordUpdate.setPassCount(passCount);
            recordUpdate.setTotalCount(totalCount);
            recordUpdate.setSuccessCount(successCount);
            LOGGER.info("[Case Update]Save record exec recordId={}, content={}", recordId, recordUpdate.toString());
            recordService.modifyRecord(recordUpdate);
        } else {
            for (String key : keys) {
                webSocket.get(key).updateRecordTime = 0L;
            }
        }
    }

    /**
     * 保存用例
     * @see #save(String, String, String)
     */
    private void saveCase(String caseId) {
        TestCase testCase = new TestCase();
        testCase.setId(Long.valueOf(caseId));
        List<String> keys = getKeysByCaseId(caseId, UNDEFINED);
        long maxTime = 0;
        String keySave = "";
        JSONObject jsonObject = new JSONObject();
        JSONObject jsonContent = new JSONObject();

        // 将用例内容更新为最新
        for (String key : keys) {
            if (webSocket.get(key).updateCaseTime > maxTime) {
                maxTime = webSocket.get(key).updateCaseTime;
                jsonObject = TreeUtil.parse(webSocket.get(key).caseContent);
                jsonContent = jsonObject.getJSONObject("content");
                keySave = key;
            }
        }

        if (StringUtils.isEmpty(keySave)) {
            // 无需更新
            return;
        }

        //对比用例http更新时间和socket更新时间
        TestCase dbCase = caseMapper.findOne(Long.valueOf(caseId));
        long tcUpdateTime = dbCase.getGmtModified().getTime();
        long wsTcUpdateTime = webSocket.get(keySave).updateCaseTime;
        //数据库更新时间大于socket最大更新时间则不需要保存
        if (tcUpdateTime >= wsTcUpdateTime) {
            for (String key : keys) {
                webSocket.get(key).caseContent = testCase.getCaseContent();
                webSocket.get(key).updateCaseTime = 0L;
            }
            return;
        }
        if (!jsonObject.containsKey("content")) {
            return;
        }
        for (String key : keys) {
            webSocket.get(key).caseContent = webSocket.get(keySave).caseContent;
            webSocket.get(key).updateCaseTime = 0L;
        }

        testCase.setCaseContent(jsonContent.toJSONString());
        Date now = new Date(wsTcUpdateTime);
        testCase.setGmtModified(now);

        caseMapper.update(testCase);
    }

    /**
     * 打开用例/任务
     * @see #onOpen(String, String, String, String, Session)
     */
    private void open(String caseId, String recordId, String isCore) throws IOException {
        String res = caseMapper.findOne(Long.valueOf(caseId)).getCaseContent();
        if (StringUtils.isEmpty(res)) {
            throw new CaseServerException("用例内容为空", StatusCode.WS_UNKNOWN_ERROR);
        }

        switch (isCore) {
            case "0": {
                // 这里是打开case的情况
                sendMessage(this.session, res);
                break;
            }
            case "3": {
                // 这里是打开record的情况
                RecordWsDto dto = recordService.getWsRecord(Long.valueOf(recordId));

                String recordContent = dto.getCaseContent();
                JSONObject recordObj = new JSONObject();
                if (StringUtils.isEmpty(recordContent)) {
                    // 其实当前任务还没有任何执行记录
                    LOGGER.info("first create record.");
                } else if (recordContent.startsWith("[{")) {
                    JSONArray jsonArray = JSON.parseArray(recordContent);
                    for (Object o : jsonArray) {
                        recordObj.put(((JSONObject) o).getString("id"), ((JSONObject) o).getLong("progress"));
                    }
                } else {
                    recordObj = JSON.parseObject(recordContent);
                }

                IntCount ExecCount = new IntCount(recordObj.size());
                // 如果当前record是圈选了部分的圈选用例
                if (!StringUtils.isEmpty(dto.getChooseContent()) && !dto.getChooseContent().contains("\"priority\":[\"0\"]")) {
                    Map<String, List<String>> chosen = JSON.parseObject(dto.getChooseContent(), Map.class);

                    JSONObject caseContent = JSON.parseObject(res);
                    JSONObject caseRoot = caseContent.getJSONObject("root");
                    Stack<JSONObject> objCheck = new Stack<>();

                    Stack<IntCount> iCheck = new Stack<>();
                    objCheck.push(caseRoot);

                    List<String> priority = chosen.get("priority");
                    List<String> resource = chosen.get("resource");
                    //获取对应级别用例
                    if (!CollectionUtils.isEmpty(priority)) {
                        TreeUtil.getPriority(objCheck, iCheck, caseRoot, priority);
                    }
                    if (!CollectionUtils.isEmpty(resource)) {
                        TreeUtil.getChosenCase(caseRoot, new HashSet<>(resource), "resource");
                    }

                    TreeUtil.mergeExecRecord(caseContent.getJSONObject("root"), recordObj, ExecCount);
                    sendMessage(this.session, caseContent.toJSONString());
                } else {
                    // 如果是全部的，那么直接把testcase 给 merge过来
                    JSONObject caseContent = JSON.parseObject(res);
                    TreeUtil.mergeExecRecord(caseContent.getJSONObject("root"), recordObj, ExecCount);
                    sendMessage(this.session, caseContent.toJSONString());
                }
            }
        }
    }

    /**
     * 封装对齐函数
     */
    public static String fill(String key) {
        return stringFill(key, 8, '0', true);
    }

    public static String stringFill(String source, int fillLength, char fillChar, boolean isLeftFill) {
        if (source == null || source.length() >= fillLength) {
            return source;
        }

        StringBuilder result = new StringBuilder(fillLength);
        int len = fillLength - source.length();
        if (isLeftFill) {
            for (; len > 0; len--) {
                result.append(fillChar);
            }
            result.append(source);
        } else {
            result.append(source);
            for (; len > 0; len--) {
                result.append(fillChar);
            }
        }
        return result.toString();
    }

    /**
     * 获取当前websocket对应的用户
     */
    public String getUser() {
        return this.user;
    }

    /**
     * 获取一类用例/任务下的所有正在编辑的人
     */
    public static List<String> getEditingUser(String caseId, String recordId) {
        lock.lock();
        try {
            // 从Websocket.keys中获取所有正在编辑的用户的前缀！
            String prefix = fill(caseId) + fill(recordId);
            // 复制当前瞬间的拷贝，不受原本对象的干扰
            Map<String, WebSocket> wsMap = new HashMap<>(WebSocket.webSocket);
            List<String> names = new ArrayList<>();
            for (Map.Entry<String, WebSocket> entry : wsMap.entrySet()) {
                if (entry.getKey() != null && entry.getKey().startsWith(prefix)) {
                    names.add(entry.getValue().getUser());
                }
            }
            return names;
        } finally {
            lock.unlock();
        }
    }

}


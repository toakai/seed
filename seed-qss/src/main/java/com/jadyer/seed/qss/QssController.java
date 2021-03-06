package com.jadyer.seed.qss;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.jadyer.seed.comm.constant.CodeEnum;
import com.jadyer.seed.comm.constant.CommResult;
import com.jadyer.seed.comm.constant.SeedConstants;
import com.jadyer.seed.comm.exception.SeedException;
import com.jadyer.seed.comm.jpa.Condition;
import com.jadyer.seed.comm.util.LogUtil;
import com.jadyer.seed.qss.model.ScheduleSummary;
import com.jadyer.seed.qss.model.ScheduleTask;
import com.jadyer.seed.qss.repository.ScheduleTaskDaoJdbc;
import com.jadyer.seed.qss.repository.ScheduleTaskRepository;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.ReflectionToStringBuilder;
import org.apache.commons.lang3.time.DateFormatUtils;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

@Controller
@RequestMapping("/qss")
public class QssController {
    @Resource
    private JedisPool jedisPool;
    @Resource
    private QssService qssService;
    @Resource
    private ScheduleTaskDaoJdbc scheduleTaskDaoJdbc;
    @Resource
    private ScheduleTaskRepository scheduleTaskRepository;

    /**
     * 此接口只做演示用：http://127.0.0.1/qss/getByIds?ids=1`2
     */
    @ResponseBody
    @RequestMapping("/getByIds")
    CommResult<String> getByIds(final String ids){
        List<Long> idList = new ArrayList<>();
        String[] idstr = ids.split("`");
        for(String obj : idstr){
            idList.add(Long.parseLong(obj));
        }
        //使用@Query查询的方式
        List<ScheduleTask> taskList = new ArrayList<>();
        Object[] objs = scheduleTaskRepository.selectByIds(idList);
        for(Object obj : objs){
            ScheduleTask task = new ScheduleTask();
            task.setName(((Object[])obj)[0].toString());
            task.setUrl(((Object[])obj)[1].toString());
            taskList.add(task);
        }
        for(ScheduleTask obj : taskList){
            System.out.println("11--查询到name=[" + obj.getName() + "]，url=[" + obj.getUrl() +"]");
        }
        //使用接口作为返回值实现多表查询
        List<ScheduleSummary> scheduleSummaryList = scheduleTaskRepository.selectByIdList(idList);
        for(ScheduleSummary obj : scheduleSummaryList){
            System.out.println("22--查询到name=[" + obj.getName() + "]，url=[" + obj.getUrl() +"]");
        }
        //查询Redis时间
        Object redisTimeObj = jedisPool.getResource().eval("return redis.call('time')");
        List<String> redisTimeList = JSON.parseObject(redisTimeObj.toString(), new TypeReference<List<String>>(){});
        return CommResult.success(JSON.toJSONString(new HashMap<String, Object>(){
            private static final long serialVersionUID = 2518882720835440047L;
            {
                put("allJob", qssService.getAllJob());
                put("allRunningJob", qssService.getAllRunningJob());
                put("taskInfo", scheduleTaskDaoJdbc.getById(Long.parseLong(ids.substring(0,1))));
                put("taskList", scheduleTaskRepository.findAll(Condition.<ScheduleTask>and().in("id", idList)));
                put("redisTime", DateFormatUtils.format(new Date(Long.parseLong(redisTimeList.get(0) + redisTimeList.get(1).substring(0, 3))), "yyyy-MM-dd HH:mm:ss.SSS"));
                put("nextFireTimes", qssService.getNextFireTimes("0 */1 * * * ?", 5));
            }
        }));
    }


    /**
     * 通过注解注册任务
     */
    @ResponseBody
    @PostMapping("/reg")
    public CommResult<Boolean> reg(ScheduleTask task, String dynamicPassword){
        this.verifyDynamicPassword(dynamicPassword);
        LogUtil.getLogger().info("收到任务注册请求：{}", ReflectionToStringBuilder.toString(task));
        if(null == scheduleTaskRepository.getByAppnameAndName(task.getAppname(), task.getName())){
            //注册过来的任务自动启动
            task.setStatus(SeedConstants.QSS_STATUS_RUNNING);
            task = scheduleTaskRepository.saveAndFlush(task);
            //通过Redis发布订阅来同步到所有QSS节点里面
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.publish(SeedConstants.CHANNEL_SUBSCRIBER, JSON.toJSONString(task));
            }
            return CommResult.success();
        }else{
            LogUtil.getLogger().info("收到任务注册请求：任务已存在：{}#{}，自动忽略...", task.getAppname(), task.getName());
            return CommResult.fail(CodeEnum.SYSTEM_BUSY.getCode(), "任务已存在："+task.getAppname()+"#"+task.getName()+"，自动忽略...");
        }
    }


    @ResponseBody
    @PostMapping("/add")
    public CommResult add(ScheduleTask task, String dynamicPassword){
        this.verifyDynamicPassword(dynamicPassword);
        ScheduleTask obj = qssService.saveTask(task);
        return CommResult.success();
    }


    @ResponseBody
    @GetMapping("/delete/{id}/{dynamicPassword}")
    public CommResult delete(@PathVariable long id, @PathVariable String dynamicPassword){
        this.verifyDynamicPassword(dynamicPassword);
        qssService.deleteTask(id);
        return CommResult.success();
    }


    @ResponseBody
    @GetMapping("/updateStatus")
    public CommResult updateStatus(long id, int status, String dynamicPassword){
        this.verifyDynamicPassword(dynamicPassword);
        if(qssService.updateStatus(id, status)){
            return CommResult.success();
        }else{
            return CommResult.fail();
        }
    }


    @ResponseBody
    @GetMapping("/updateCron")
    public CommResult updateCron(long id, String cron, String dynamicPassword){
        this.verifyDynamicPassword(dynamicPassword);
        if(qssService.updateCron(id, cron)){
            return CommResult.success();
        }else{
            return CommResult.fail();
        }
    }


    /**
     * 立即执行一个QuartzJOB
     */
    @ResponseBody
    @GetMapping("/triggerJob/{id}/{dynamicPassword}")
    public CommResult triggerJob(@PathVariable long id, @PathVariable String dynamicPassword){
        this.verifyDynamicPassword(dynamicPassword);
        qssService.triggerJob(id);
        return CommResult.success();
    }


    /**
     * 验证动态密码是否正确（每个动态密码有效期为10分钟）
     */
    private void verifyDynamicPassword(String dynamicPassword){
        if(StringUtils.isBlank(dynamicPassword)){
            throw new SeedException(CodeEnum.SYSTEM_BUSY.getCode(), "动态密码不能为空");
        }
        if(!dynamicPassword.contains("jadyer")){
            String timeFlag = DateFormatUtils.format(new Date(), "HHmm").substring(0, 3) + "0";
            String generatePassword = DigestUtils.md5Hex(timeFlag + "https://jadyer.cn/" + timeFlag);
            if(!generatePassword.equals(dynamicPassword)){
                throw new SeedException(CodeEnum.SYSTEM_BUSY.getCode(), "动态密码不正确");
            }
        }
    }
}
package com.k2data.yn.test;

import com.k2data.yn.test.common.GlobalConfig;
import com.k2data.yn.test.flink.*;
import com.k2data.yn.test.pojo.*;
import com.k2data.yn.test.rpc.XmlRPCUtils;
import org.apache.flink.api.common.functions.*;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.typeinfo.TypeHint;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.AllWindowedStream;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.windowing.assigners.SlidingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.windows.Window;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer011;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaProducer011;
import org.apache.flink.util.Collector;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class JobDemo3_11 {

    private final static Logger LOGGER = LoggerFactory.getLogger(JobDemo3_11.class);

    public static void main(String[] args) throws Exception {
        // Checking input parameters
        final ParameterTool params = ParameterTool.fromArgs(args);

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        // configure your test environment
        env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);
        env.setParallelism(1);

        // make parameters available in the web interface
        env.getConfig().setGlobalJobParameters(params);


        // 控制参数
        String rpcChannel = "10.1.10.21:5568";

        String jobName = JobDemo3_11.class.getSimpleName();
        Map<String, String> tags = new HashMap<>();
        tags.put("deviceId", "device_11001");

        String operatorName = "omen_var_greater_than_proportional_standard";

        String windowType = GlobalConfig.WINDOW_TYPE;  //count | time
        long windowSize = 1;
        long windowStep = 1;

        //控制参数
        Map<String, Object> kwargs = new HashMap<>();
        kwargs.put("proportion", 0.9);

        String inputType = "kafka";
        List<InputParam> inputParams = new LinkedList<>();
        Map<String, Storage> storage1 = new HashMap<>();
        storage1.put(inputType, new KafkaStorage("10.1.10.21:9092", "esf-wenfei", "F12"));
        inputParams.add(new InputParam("var", "double", 1000, false, storage1));
        Map<String, Storage> storage2 = new HashMap<>();
        storage2.put(inputType, new KafkaStorage("10.1.10.21:9092", "JobDemo3_2", "var_standard_recently"));
        inputParams.add(new InputParam("var_standard", "double", 1000, false, storage2));


        String outputType = "kafka";
        Map<String, Storage> outputStorage1 = new HashMap<>();
        outputStorage1.put(outputType, new KafkaStorage("10.1.10.21:9092", jobName, "flow_greater_than_90standard_result"));
        outputStorage1.put("influxdb", new InfluxdbStorage("http://10.1.10.21:8086", "k2data", "K2data1234", "esf", "device_11001_result_yntest", "flow_greater_than_90standard_result"));
        List<OutputParam> outputParams = new LinkedList<>();
        outputParams.add(new OutputParam("flow_greater_than_90standard_result", "bool", outputStorage1));


        //**************
        //输入变量
        //**************

        DataStream<PointData> inputStream1 = null;
        DataStream<PointData> inputStream2 = null;

        //按秒补齐数据
        DataStream<PointData> heartbeatStream = env.addSource(new TimerSource(windowStep * 1000))
                .assignTimestampsAndWatermarks(new EventTimeAssigner());

        if ("kafka".equalsIgnoreCase(inputType)) {
            Properties consumerProperties1 = new Properties();
            KafkaStorage kafkaStorage1 = (KafkaStorage) inputParams.get(0).getStorage().get(inputType);
            consumerProperties1.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaStorage1.getBootstrapServers());
            consumerProperties1.setProperty(ConsumerConfig.GROUP_ID_CONFIG, jobName + "-consumer");
            FlinkKafkaConsumer011<String> inputConsumer1 = new FlinkKafkaConsumer011<String>(kafkaStorage1.getTopic(),
                    new SimpleStringSchema(),
                    consumerProperties1);
            inputConsumer1.setStartFromLatest();
            inputStream1 = env.addSource(inputConsumer1)
                    .flatMap(new OPCJsonDataParserMapFunction())
                    .filter((FilterFunction<PointData>) value -> kafkaStorage1.getPointName().equals(value.getPointName()))
                    .assignTimestampsAndWatermarks(new EventTimeAssigner());
            if (inputParams.get(0).isFill()) {
                inputStream1 = new FillNA(windowStep, kafkaStorage1.getPointName(), heartbeatStream).doOperation(inputStream1)
                        .assignTimestampsAndWatermarks(new EventTimeAssigner());
            }

            Properties consumerProperties2 = new Properties();
            KafkaStorage kafkaStorage2 = (KafkaStorage) inputParams.get(1).getStorage().get(inputType);
            consumerProperties2.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaStorage2.getBootstrapServers());
            consumerProperties2.setProperty(ConsumerConfig.GROUP_ID_CONFIG, jobName + "-consumer");
            FlinkKafkaConsumer011<String> inputConsumer2 = new FlinkKafkaConsumer011<String>(kafkaStorage2.getTopic(),
                    new SimpleStringSchema(),
                    consumerProperties2);
            inputConsumer2.setStartFromLatest();
            inputStream2 = env.addSource(inputConsumer2)
                    .flatMap(new OPCJsonDataParserMapFunction())
                    .filter((FilterFunction<PointData>) value -> kafkaStorage2.getPointName().equals(value.getPointName()))
                    .assignTimestampsAndWatermarks(new EventTimeAssigner());
            if (inputParams.get(1).isFill()) {
                inputStream2 = new FillNA(windowStep, kafkaStorage2.getPointName(), heartbeatStream).doOperation(inputStream2)
                        .assignTimestampsAndWatermarks(new EventTimeAssigner());
            }
        }


        //合并输入数据
        DataStream<List<PointData>> joinedInputStream = inputStream1
                .join(inputStream2)
                .where((KeySelector<PointData, Long>) value -> value.getTimestamp())
                .equalTo((KeySelector<PointData, Long>) value -> value.getTimestamp())
                .window(TumblingEventTimeWindows.of(Time.seconds(windowStep)))
                .apply(new JoinFunction<PointData, PointData, List<PointData>>() {
                    @Override
                    public List<PointData> join(PointData first, PointData second) throws Exception {
                        return Arrays.asList(first, second);
                    }
                });

        //设定算子执行窗口大小和步长
        AllWindowedStream<List<PointData>, ? extends Window> windowedStream = null;
        if ("count".equals(windowType)) {  //按数据个数设定窗口
            windowedStream = joinedInputStream
                    .countWindowAll(windowSize, windowStep);
        } else {  //按数据时间设定数据窗口
            windowedStream = joinedInputStream
                    .windowAll(SlidingEventTimeWindows.of(Time.seconds(windowSize), Time.seconds(windowStep)));
        }

        //调用RPC执行算子脚本
        DataStream<List<PointData>> rpcStream = windowedStream
                .aggregate(new AggregateFunction<List<PointData>, List<List<PointData>>, List<List<PointData>>>() {
                    @Override
                    public List<List<PointData>> createAccumulator() {
                        return new LinkedList<>();
                    }

                    @Override
                    public List<List<PointData>> add(List<PointData> value, List<List<PointData>> accumulator) {
                        int i = 0;
                        for (; i < accumulator.size(); i++) {
                            if (value.get(0).getTimestamp() < accumulator.get(i).get(0).getTimestamp()) {
                                break;
                            }
                        }
                        accumulator.add(i, value);
                        return accumulator;
                    }

                    @Override
                    public List<List<PointData>> getResult(List<List<PointData>> accumulator) {
                        return accumulator;
                    }

                    @Override
                    public List<List<PointData>> merge(List<List<PointData>> a, List<List<PointData>> b) {
                        return null;
                    }
                })
                .keyBy((KeySelector<List<List<PointData>>, Long>) value -> 1L)  //这里构造一个假的key值，因为我们所有的计算在一个group中进行
                .map(new RichMapFunction<List<List<PointData>>, List<PointData>>() {

                    private transient ValueState<List<Object>> lastRPCResultState;  //上次算子执行的结果

                    @Override
                    public void open(Configuration parameters) throws Exception {
                        ValueStateDescriptor<List<Object>> descriptor =
                                new ValueStateDescriptor<>(
                                        "lastRPCResultState", // the state name
                                        TypeInformation.of(new TypeHint<List<Object>>() {
                                        }) // type information
                                );
                        lastRPCResultState = getRuntimeContext().getState(descriptor);
                    }

                    @Override
                    public List<PointData> map(List<List<PointData>> value) throws Exception {

                        //输入数据
                        Map<String, List<Object>> data = new HashMap<>();
                        List<Object> index = new LinkedList<>();
                        for (InputParam inputParam : inputParams) {
                            data.put(inputParam.getParamName(), new LinkedList<>());
                        }
                        for (List<PointData> row : value) {
                            for (int i = 0; i < inputParams.size(); i++) {
                                data.get(inputParams.get(i).getParamName())
                                        .add(row.get(i).getValue());
                            }
                            index.add(row.get(0).getTimestamp());  //每行数据的时间戳作为dataframe的index
                        }

                        //上次rpc运算结果
                        List<Object> lastRPCResult = lastRPCResultState.value();
                        if (lastRPCResult != null) {
                            for (int i = 0; i < outputParams.size(); i++) {
                                kwargs.put(outputParams.get(i).getParamName(), lastRPCResult.get(i));
                            }
                        }

                        //调用RPC
                        Object[] response = XmlRPCUtils.callRPC(rpcChannel, operatorName, data, index, kwargs);

                        //输出结果
                        if (response.length > 0) {
                            //更新计算结果到状态存储
                            lastRPCResultState.update(Arrays.asList(response));

//                            long timestamp = value.get(0).get(0).getTimestamp();   //这里取每组数据的最小时间戳作为此次rpc计算结果的时间戳
                            long timestamp = value.get(value.size() - 1).get(0).getTimestamp();   //这里取每组数据的最大时间戳作为此次rpc计算结果的时间戳
                            List<PointData> result = new LinkedList<>();
                            for (int i = 0; i < outputParams.size(); i++) {
                                result.add(new PointData(outputParams.get(i).getParamName(),
                                        timestamp,
                                        response[i],
                                        outputParams.get(i).getType()));
                            }
                            return result;
                        }
                        return null;
                    }
                })
                .filter((FilterFunction<List<PointData>>) value -> value != null);


        //存储结果
        DataStream<PointData> resultStream = rpcStream.flatMap(new FlatMapFunction<List<PointData>, PointData>() {
            @Override
            public void flatMap(List<PointData> value, Collector<PointData> out) throws Exception {
                for (PointData p : value) {
                    out.collect(p);
                }
            }
        });
        for (int i = 0; i < outputParams.size(); i++) {
            OutputParam outputParam = outputParams.get(i);
            Map<String, Storage> storageMap = outputParam.getStorage();
            for (String storageType : storageMap.keySet()) {
                if ("influxdb".equalsIgnoreCase(storageType)) {
                    InfluxdbStorage outputInfluxdbStorage = (InfluxdbStorage) storageMap.get(storageType);
                    resultStream.filter((FilterFunction<PointData>) value -> outputParam.getParamName().equals(value.getPointName()))
                            .addSink(new InfluxdbSinker(outputInfluxdbStorage.getUrl(),
                                    outputInfluxdbStorage.getUser(),
                                    outputInfluxdbStorage.getPassword(),
                                    outputInfluxdbStorage.getDatabase(),
                                    outputInfluxdbStorage.getMeasurement(),
                                    tags.get("deviceId"),
                                    jobName));
                } else if ("kafka".equalsIgnoreCase(storageType)) {
                    KafkaStorage outputKafkaStorage = (KafkaStorage) storageMap.get(storageType);

                    Properties producerProperties = new Properties();
                    producerProperties.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, outputKafkaStorage.getBootstrapServers());
                    producerProperties.setProperty(ProducerConfig.LINGER_MS_CONFIG, "3000");

                    FlinkKafkaProducer011<String> resultProducer = new FlinkKafkaProducer011<String>(
                            outputKafkaStorage.getTopic(),
                            new SimpleStringSchema(),
                            producerProperties,
                            Optional.empty()  //round-robin to all partitions
                    );
                    resultStream.filter((FilterFunction<PointData>) value -> outputParam.getParamName().equals(value.getPointName()))
                            .map((MapFunction<PointData, String>) value -> {
                                StringBuilder sb = new StringBuilder();
                                sb.append("[");
                                sb.append(value.dump());
                                sb.append("]");
                                return sb.toString();
                            })
                            .addSink(resultProducer);
                }
            }
        }


        //执行作业
        env.execute(jobName);
    }

}

package com.berry.oss.service.impl;

import com.alibaba.fastjson.JSON;
import com.berry.oss.common.utils.HttpClient;
import com.berry.oss.common.utils.StringUtils;
import com.berry.oss.core.entity.BucketInfo;
import com.berry.oss.core.service.IRegionInfoDaoService;
import com.berry.oss.erasure.ReedSolomon;
import com.berry.oss.module.dto.ServerListDTO;
import com.berry.oss.remote.WriteShardResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Command-line program encodes one file using Reed-Solomon 4+2.
 * <p>
 * The one argument should be a file name, say "foo.txt".  This program
 * will create six files in the same directory, breaking the input file
 * into four data shards, and two parity shards.  The output files are
 * called "foo.txt.0", "foo.txt.1", ..., and "foo.txt.5".  Numbers 4
 * and 5 are the parity shards.
 * <p>
 * The data stored is the file size (four byte int), followed by the
 * contents of the file, and then padded to a multiple of four bytes
 * with zeros.  The padding is because all four data shards must be
 * the same size.
 */
@Service
public class ReedSolomonEncoderService {
    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    /**
     * 数据分片数
     */
    private static final int DATA_SHARDS = 4;
    /**
     * 奇偶校验分片数
     */
    private static final int PARITY_SHARDS = 2;

    /**
     * 分片总数
     */
    private static final int TOTAL_SHARDS = DATA_SHARDS + PARITY_SHARDS;

    /**
     * 数据分片增加 4B 信息头，存放数据长度信息
     */
    private static final int BYTES_IN_INT = DATA_SHARDS;

    private final IRegionInfoDaoService regionInfoDaoService;

    public ReedSolomonEncoderService(IRegionInfoDaoService regionInfoDaoService) {
        this.regionInfoDaoService = regionInfoDaoService;
    }

    /**
     * 将输入流，分片 4+2 保存
     *
     * @param inputStream 输入流
     * @param fileName    文件名
     * @param bucketInfo  存储空间
     * @param username    用户名
     * @return 对象唯一标识id
     * @throws IOException
     */
    public String writeData(InputStream inputStream, String fileName, BucketInfo bucketInfo, String username) throws IOException {

        // Get the size of the input file.  (Files bigger that
        // Integer.MAX_VALUE will fail here!) 最大 2G
        final int fileSize = inputStream.available();

        // 计算每个数据分片大小.  (文件大小 + 4个数据分片头) 除以 4 向上取整
        final int storedSize = fileSize + BYTES_IN_INT;
        final int shardSize = (storedSize + DATA_SHARDS - 1) / DATA_SHARDS;

        // 创建一个 4 个数据分片大小的 buffer
        final int bufferSize = shardSize * DATA_SHARDS;
        final byte[] allBytes = new byte[bufferSize];

        // buffer前4个字节（4B）写入数据长度
        ByteBuffer.wrap(allBytes).putInt(fileSize);

        // 读入文件到 字节数组（allBytes）
        int bytesRead = inputStream.read(allBytes, BYTES_IN_INT, fileSize);
        if (bytesRead != fileSize) {
            throw new IOException("not enough bytes read");
        }
        inputStream.close();

        // 创建二维字节数组，将 文件字节数组 （allBytes）copy到该数组（shards）
        byte[][] shards = new byte[TOTAL_SHARDS][shardSize];

        // Fill in the data shards
        for (int i = 0; i < DATA_SHARDS; i++) {
            System.arraycopy(allBytes, i * shardSize, shards[i], 0, shardSize);
        }

        // 使用 Reed-Solomon 算法计算 2 个奇偶校验分片.
        ReedSolomon reedSolomon = ReedSolomon.create(DATA_SHARDS, PARITY_SHARDS);
        reedSolomon.encodeParity(shards, 0, shardSize);

        // 获取该存储空间的 6 个 可用服务器列表
        List<ServerListDTO> serverList = regionInfoDaoService.getServerListByRegionIdLimit(bucketInfo.getRegionId(), TOTAL_SHARDS);
        if (serverList.size() != TOTAL_SHARDS) {
            // 数据写入服务不可用
            throw new RuntimeException("数据写入服务不可用");
        }

        List<WriteShardResponse> result = new ArrayList<>(16);

        Map<String, Object> params = new HashMap<>(16);
        params.put("username", username);
        params.put("bucketName", bucketInfo.getName());
        params.put("fileName", fileName);
        // 数据分片分发
        for (int i = 0; i < TOTAL_SHARDS; i++) {
            ServerListDTO server = serverList.get(i);
            params.put("shardIndex", i);
            params.put("data", shards[i]);
            String basePath = "http://" + server.getIp() + ":" + server.getPort() ;
            String writePath = HttpClient.doPost(basePath + "/data/write", params);
            if (StringUtils.isBlank(writePath)) {
                logger.error("数据写入失败，index:{},服务：{}", i, basePath + "/data/write");
                throw new RuntimeException("数据写入失败");
            }
            result.add(new WriteShardResponse(basePath + "/data/read", writePath));
        }
        return JSON.toJSONString(result);
    }
}

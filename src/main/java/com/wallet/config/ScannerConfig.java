package com.wallet.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

/**
 * Web3j + 定时任务配置
 */
@Configuration
@EnableScheduling
public class ScannerConfig {

    @Bean
    public Web3j evmWeb3j(@org.springframework.beans.factory.annotation.Value("${wallet.chain.evm-rpc-url}") String rpcUrl) {
        return Web3j.build(new HttpService(rpcUrl));
    }
}

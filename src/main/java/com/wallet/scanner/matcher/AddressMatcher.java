package com.wallet.scanner.matcher;

import com.wallet.enums.ChainType;
import com.wallet.scanner.entity.AddressIndex;
import com.wallet.scanner.parser.TransactionParser.ParsedTransfer;
import com.wallet.scanner.repository.AddressIndexRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 地址匹配器 —— Phase 1 最简版：直接查 DB
 * 未来百万级地址时，先加 Bloom Filter 内存预判
 *
 * 职责：把 ParsedTransfer 的收款地址与交易所维护的地址表（AddressIndex）比对
 * 只返回匹配上的 Transfer（收款方是交易所地址才算充值）
 */
@Component
public class AddressMatcher {

    private static final Logger log = LoggerFactory.getLogger(AddressMatcher.class);

    private final AddressIndexRepository addressIndexRepo;

    public AddressMatcher(AddressIndexRepository addressIndexRepo) {
        this.addressIndexRepo = addressIndexRepo;
    }

    /**
     * 过滤出收款方是交易所地址的 transfer，同时补上 userId / addressType
     */
    public List<MatchedTransfer> match(List<ParsedTransfer> transfers, ChainType chainType) {
        List<MatchedTransfer> result = new ArrayList<>();
        for (ParsedTransfer t : transfers) {
            Optional<AddressIndex> opt = addressIndexRepo.findByChainTypeAndAddress(chainType, t.to);
            if (opt.isPresent()) {
                AddressIndex idx = opt.get();
                MatchedTransfer mt = new MatchedTransfer();
                mt.transfer = t;
                mt.userId = idx.getUserId();
                mt.addressType = idx.getAddressType();
                result.add(mt);
            }
            // 没匹配上 = 转给了非交易所地址，忽略
        }
        if (!result.isEmpty()) {
            log.debug("[Matcher] chain={} matched {}/{} transfers", chainType, result.size(), transfers.size());
        }
        return result;
    }

    /** 匹配后的 Transfer：带上了交易所地址的 userId / addressType */
    public static class MatchedTransfer {
        public ParsedTransfer transfer;
        public String userId;       // 可能为 null（热/归集/冷钱包）
        public String addressType;  // USER_DEPOSIT / HOT_WALLET / ...
    }
}

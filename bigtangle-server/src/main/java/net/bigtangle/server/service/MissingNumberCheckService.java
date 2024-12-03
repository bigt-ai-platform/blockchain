package net.bigtangle.server.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import net.bigtangle.core.TXReward;

public class MissingNumberCheckService {

    public boolean check(List<TXReward> sequence) throws ExecutionException, InterruptedException {

        Long start = 1l;
        Long end = new Long(sequence.size());
        ExecutorService executor = Executors.newFixedThreadPool(4);
        List<Future<List<Long>>> futureList = new ArrayList<>();
        List<Long> missingNumbers = new ArrayList<>();
        for (Long i = start; i <= end; i += 1000) {
            Callable<List<Long>> callable = new MissingNumberCheck(i, i + 999l, sequence);
            Future<List<Long>> future = executor.submit(callable);
            futureList.add(future);
        }

        for (Future<List<Long>> future : futureList) {
            missingNumbers.addAll(future.get());
        }

        for (Long missingNumber : missingNumbers) {

            System.out.println(missingNumber + " is missing from the sequence. size =  " + sequence.size());

        }
        executor.shutdown();
        return missingNumbers.isEmpty();

    }

    static class MissingNumberCheck implements Callable<List<Long>> {
        private Long start;
        private Long end;
        private List<TXReward> sequence;

        public MissingNumberCheck(Long start, Long end, List<TXReward> sequence) {
            this.start = start;
            this.end = end;
            this.sequence = sequence;
        }

        @Override
        public List<Long> call() throws Exception {
            List<Long> missingNumbers = new ArrayList<Long>();
            for (Long i = start; i <= end; i++) {
                if (!contain(i)) {
                    missingNumbers.add(i);
                }
            }
            return missingNumbers;
        }

        public boolean contain(Long number) {
            if (number >= sequence.size())
                return true;
            for (TXReward t : sequence) {
                if (t.getChainLength() == number) {
                    return true;
                }
            }
            return false;
        }
    }
}

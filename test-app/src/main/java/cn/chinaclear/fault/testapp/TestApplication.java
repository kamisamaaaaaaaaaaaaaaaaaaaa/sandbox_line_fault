package cn.chinaclear.fault.testapp;

import cn.chinaclear.fault.testlib.AmountChecker;
import cn.chinaclear.fault.testlib.IdGenerator;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * 验证应用主类：启动期 CommandLineRunner 多行逻辑，
 * 用于验证「启动期用户代码也会被 hook（kill 可能发生在启动过程中）」。
 */
@SpringBootApplication
public class TestApplication {

    private final ApplicationContext applicationContext;

    public TestApplication(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    public static void main(String[] args) {
        SpringApplication.run(TestApplication.class, args);
    }

    @Bean
    public IdGenerator idGenerator() {
        IdGenerator generator = new IdGenerator();
        return generator;
    }

    @Bean
    public AmountChecker amountChecker() {
        AmountChecker checker = new AmountChecker();
        return checker;
    }

    @Bean
    public CommandLineRunner startupRunner() {
        return args -> {
            String banner = "fault-test-app starting up";
            System.out.println(banner);
            long start = System.currentTimeMillis();
            int beanCount = applicationContext.getBeanDefinitionCount();
            System.out.println("bean count = " + beanCount);
            IdGenerator generator = idGenerator();
            String demoOrderId = generator.nextOrderId();
            System.out.println("demo order id = " + demoOrderId);
            long cost = System.currentTimeMillis() - start;
            System.out.println("startup runner cost = " + cost + "ms");
            System.out.println("startup done, ready for requests");
        };
    }
}

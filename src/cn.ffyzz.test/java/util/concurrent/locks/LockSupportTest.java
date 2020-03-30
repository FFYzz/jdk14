package java.util.concurrent.locks;

/**
 * @Title:
 * @Author: FFYzz
 * @Mail: cryptochen95@gmail.com
 * @Date: 2020/3/23
 */
public class LockSupportTest {

    public static void main(String[] args) throws InterruptedException {
        Thread t1 = new Thread(() -> {
            LockSupport.park();
            System.out.println("in thread");
        });
        t1.start();
        Thread.sleep(5000);
        System.out.println("in main");
        LockSupport.unpark(t1);
        t1.join();
        System.out.println("end");
    }

}

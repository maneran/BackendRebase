

public class MultiThreading {

    static int counter = 0;

    public static void main(String[] args) throws InterruptedException {
        Thread thread1 = new Thread(MultiThreading::increasBy10);
        Thread thread2 = new Thread(MultiThreading::increasBy10);

        thread1.start();
        thread2.start();
        thread1.join();
        thread2.join();
        System.out.println("Counter: " + counter);
    }


    public static void increasBy10() {
        for(int i = 0; i < 10; i++) {
            counter++;
        }
    }
}

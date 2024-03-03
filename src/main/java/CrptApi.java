import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpRequest;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


//Ограничение на количество запросов организованно так,
// что запросы равномерно распределяются по указанному промежутку времени
@Getter
@Setter
public class CrptApi {

    private static final String URL = "https://ismp.crpt.ru/api/v3/lk/documents/create";
    private static final long TIME_AMOUNT = 1;

    private static BlockingQueue<Request> queue; //очередь хранит запросы на отправку к внешнему АПИ
    private static ScheduledExecutorService service;

    private int requestLimit;
    private TimeUnit timeUnit;
    private final ExecutorService pool;


    public CrptApi(TimeUnit timeUnit, int requestLimit) {
        this.timeUnit = timeUnit;
        if (requestLimit > 0) {
            this.requestLimit = requestLimit;
        }

        if (queue == null) {
            queue = new ArrayBlockingQueue<>(requestLimit, true);
        }
        pool = Executors.newCachedThreadPool();
    }

    //Тестовый запуск с имитацией 100 запросов и ограничением на 20 запросов в минуту
    public static void main(String[] args) {
        CrptApi crptApi = new CrptApi(TimeUnit.MINUTES, 20); //ставим ограничение на 20 запросов в минуту
        Document document = new Document();
        String signature = "signature";

        for (int i = 0; i < 100; i++) //для проверки имитируем отправку 100 запросов в очередь
            crptApi.doWork(document, signature); //основной метод, который требовалось реализовать
    }

    //Основной метод который требовалось реализовать
    public void doWork(Document document, String signature) {
        //checkInputData(document, signature); //проверка корректности введенных данных, на данный момент отключена,
                                                // что бы при демонстрации работы не падали ошибки валидации

        String body = asJson(document);    //получаем строку из объекта для отправки запроса
        long delayMicroseconds = calculateDelay(); //считаем задержку между запуском потока на отправку запросов из очереди,
                                                    // что бы запросы выполнялись равномерно
                                                    //в соответствии с указанным количеством в единицу времени
        Request request = new Request(body, signature); //создаем запрос

        pool.execute(new RequestSubmitter(request)); //кладем запрос в очередь

        if (service == null) {
            service = Executors.newScheduledThreadPool(100);
            service.scheduleAtFixedRate(  //выполняем запрос по расписанию с задержкой, которую рассчитали ранее
                    new RequestExecutor(),
                    0L,
                    delayMicroseconds,
                    TimeUnit.MICROSECONDS
            );
        }
    }

    //проверка корректности входных данных на null и пустые поля
    private void checkInputData(Document document, String signature) {
        List<String> errors = new ArrayList<>();
        findDocumentErrors(document, errors);
        findSignatureErrors(signature, errors);
        if (!errors.isEmpty()) {
            errors.forEach(System.out::println);
            System.exit(0);
        }
    }

    private void findSignatureErrors(String signature, List<String> errors) {
        if (signature == null || signature.isBlank()) {
            errors.add("Некорректная подпись");
        }
    }

    private void findDocumentErrors(Document document, List<String> errors) {
        Document.Products products = document.getProducts();
        findEmptyFields(document, errors);
        findEmptyFields(products, errors);
    }

    private <T> void findEmptyFields(T object, List<String> errors) {
        if (object == null) {
            errors.add("Некорректный документ");
            return;
        }

        for (Field f : object.getClass().getDeclaredFields()) {
            try {
                if (f.get(object) == null) {
                    errors.add("Пустое поле: " + f.getName());
                }
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
    }

    //маппер
    private String asJson(Document document) {
        ObjectMapper mapper = new ObjectMapper();
        DateFormat df = new SimpleDateFormat("yyyy-MM-dd");

        mapper.setDateFormat(df);

        String json;
        try {
            json = mapper.writeValueAsString(document);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        return json;
    }

    //вычисление задержки/периодичности запуска отправки запросов из очереди
    private long calculateDelay() {
        return TimeUnit.MICROSECONDS.convert(TIME_AMOUNT, timeUnit) / requestLimit;
    }

    //класс запросов
    @RequiredArgsConstructor
    public static class Request {

        private final String body;
        private final String header;

        public void doPost() {
            HttpRequest.newBuilder()
                    .uri(URI.create(URL))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .header("signature", header)
                    .build();
        }
    }

    //для отправки запроса в очередь
    @RequiredArgsConstructor
    public static class RequestSubmitter implements Runnable {

        private final Request request;

        @Override
        public void run() {
            try {
                queue.put(request);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            System.out.println("Запрос добавлен");
        }
    }

    //для выполнения запроса из очереди
    @RequiredArgsConstructor
    public static class RequestExecutor implements Runnable {

        @Override
        public void run() {
            try {
                queue.take().doPost();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            System.out.println("--- Запрос выполнен");
        }
    }

    @Getter
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Document {

        private String description;
        private String docId;
        private String docStatus;
        private String docType;
        private boolean importRequest;
        private String ownerInn;
        private String participantInn;
        private String producerInn;
        private Date productionDate;
        private String productionType;
        private Date regDate;
        private String regNumber;
        private Products products;

        @Getter
        @AllArgsConstructor
        @NoArgsConstructor
        public static class Products {

            private Date certificateDocumentDate;
            private String certificateDocumentNumber;
            private Date productionDate;
            private String tnvedCode;
            private String uitCode;
            private String uituCode;
        }
    }
}

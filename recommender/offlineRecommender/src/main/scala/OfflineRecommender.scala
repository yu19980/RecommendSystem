import org.apache.spark.SparkConf
import org.apache.spark.mllib.recommendation.{ALS, Rating}
import org.apache.spark.sql.SparkSession
import org.jblas.DoubleMatrix

object OfflineRecommender {


  // MongoDB collections
  val MOVIES_COLLECTION_NAME = "Movie"
  val RATING_COLLECTION_NAME = "Rating"
  val TAG_COLLECTION_NAME = "Tag"

  // The collection to store the Collaborative filtering result
  val USER_RECOMMENDATIONS_COLLECTION_NAME = "UserRecommendations"
  val MOVIE_SIMILARITIES_COLLECTION_NAME = "MovieSimilarities"

  val USER_MAX_RECOMMENDATION = 10
  val MOVIE_MAX_RECOMMENDATION = 10

  def main(args: Array[String]): Unit = {
    // Set the general parameters
    val params = scala.collection.mutable.Map[String, Any]()
    params += "spark.cores" -> "local[2]"
    params += "mongo.uri" -> "mongodb://bigdata112:27017/recom"
    params += "mongo.db" -> "recom"

    // Configure spark
    val conf = new SparkConf().setMaster("SatisticsApp").setMaster(params("spark.cores").asInstanceOf[String])
    val spark = SparkSession.builder().config(conf).getOrCreate()

    // generate MongoConfig
    implicit val mongoConfig = new MongoConfig(params("mongo.uri").asInstanceOf[String], params("mongo.db").asInstanceOf[String])

    // Read data from MongoDB
    import spark.implicits._
    val movieRating = spark.read
      .option("uri", mongoConfig.uri)
      .option("collection", RATING_COLLECTION_NAME)
      .format("com.mongodb.spark.sql")
      .load()
      .as[MovieRating]
      .rdd
      .map(rating => (rating.uid, rating.mid, rating.score))
      .cache()

    val movies = spark.read
      .option("uri", mongoConfig.uri)
      .option("collection", MOVIES_COLLECTION_NAME)
      .format("com.mongodb.spark.sql")
      .load()
      .as[Movie]
      .rdd
      .map(_.mid)
      .cache()

    // Construct training data
    val trainData = movieRating.map(x => Rating(x._1, x._2, x._3))
    val (rank, iterations, lamda) = (50, 5, 0.1)

    // Training model
    val model = ALS.train(trainData, rank, iterations, lamda)

    // Construct recommend matrix
    // user * movie
    val userRDD = movieRating.map(_._1).distinct().cache()
    val userMovie = userRDD.cartesian(movies)

    // Predict
    val predictedRating = model.predict(userMovie)

    // Filter
    val userRecommendations = predictedRating
      .filter(_.rating > 0)
      .map(x => (x.user, (x.product, x.rating)))
      .groupByKey()
      .map {
        case (userID, movieRating) => {
          UserRecommendations(userID, movieRating.toList.sortWith(_._2 > _._2).take(USER_MAX_RECOMMENDATION).map(x => Recommendation(x._1, x._2)))
        }
      }.toDF

    // Store the result back to the MongoDB
    userRecommendations.write
      .option("uri", mongoConfig.uri)
      .option("collection", USER_RECOMMENDATIONS_COLLECTION_NAME)
      .mode("overwrite")
      .format("com.mongodb.spark.sql")
      .save()

    // Calculate the movie similarity matrix

    // Movie product features matrix
    val movieFeatures = model.productFeatures //(Int, Array[Double])
      .map {
        case (mid, features) => (mid, new DoubleMatrix(features))
      }

    // Calculate similarity
    val movieSimilarities = movieFeatures.cartesian(movieFeatures)
      .filter {
        case (a, b) => a._1 != b._1
      }.map {
      case (a, b) => {
        val simScore = this.consineSimilarity(a._2, b._2)
        (a._1, (b._1, simScore))
      }
    }.filter(_._2._2 > 0.6)
      .groupByKey()
      .map {
        case (mid, items) => {
          MovieSimilaritys(mid, items.toList.sortWith(_._2 > _._2).map(x => Recommendation(x._1, x._2)))
        }
      }.toDF


    movieSimilarities.write
      .option("uri", mongoConfig.uri)
      .option("collection", MOVIE_SIMILARITIES_COLLECTION_NAME)
      .mode("overwrite")
      .format("com.mongodb.spark.sql")
      .save()

    // Release the memory
    movieRating.unpersist()
    movies.unpersist()
    userRDD.unpersist()

    spark.close()
  }

  def consineSimilarity(movie1: DoubleMatrix, movie2: DoubleMatrix): Double = {
    movie1.dot(movie2) / (movie1.norm2() * movie2.norm2())
  }
}

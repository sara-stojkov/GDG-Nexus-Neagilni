from model.inference import AllergyInference

inference = AllergyInference(
    species_list=["nettle", "grass", "trees"],
    model_path="artifacts/models/model.pt"
)

def get_prediction(api_data):
    result = inference.predict(api_data["pollen"])
    return result


if __name__ == "__main__":
    print(get_prediction)
